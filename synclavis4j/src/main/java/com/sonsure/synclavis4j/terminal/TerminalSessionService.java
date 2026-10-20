package com.sonsure.synclavis4j.terminal;

import com.sonsure.synclavis4j.plugin.PluginService;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 终端会话生命周期（见《详细设计文档》§6.6）。
 *
 * <p>创建会话只登记元数据；第一个 WebSocket 连接建立时才启动 PTY。
 * 进程只存在于运行时，WebSocket 断开或插件权限撤销时立即回收。</p>
 */
@Service
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);
    private static final String TERMINAL_PERMISSION = "terminal";
    private static final int MIN_COLS = 1;
    private static final int MAX_COLS = 500;
    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 500;
    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final int MAX_INPUT_CHARS = 64 * 1024;
    private static final long STALE_CREATED_MS = 30 * 1000L;

    private final PluginService plugins;
    private final RepoService repos;
    private final TerminalProcessFactory processFactory;
    private final boolean enabled;
    private final String shellOverride;
    private final int maxSessions;
    private final Map<String, TerminalSession> sessions =
            new ConcurrentHashMap<String, TerminalSession>();
    private final Object createLock = new Object();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "synclavis-terminal-cleaner");
                thread.setDaemon(true);
                return thread;
            });

    public TerminalSessionService(PluginService plugins, RepoService repos,
                                  TerminalProcessFactory processFactory,
                                  @Value("${synclavis.terminal.enabled:true}") boolean enabled,
                                  @Value("${synclavis.terminal.shell:}") String shellOverride,
                                  @Value("${synclavis.terminal.max-sessions:8}") int maxSessions) {
        this.plugins = plugins;
        this.repos = repos;
        this.processFactory = processFactory;
        this.enabled = enabled;
        this.shellOverride = trimToNull(shellOverride);
        this.maxSessions = Math.max(1, maxSessions);
        this.cleaner.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    cleanupStale();
                } catch (RuntimeException e) {
                    log.debug("清理终端会话失败", e);
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    public TerminalSessionDto create(CreateTerminalRequest request) {
        requireAccess();
        if (request == null || trimToNull(request.getRepoId()) == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "缺少 repoId");
        }
        String repoId = request.getRepoId().trim();
        String cwd = repos.pathOf(repoId);
        requireDirectory(cwd);

        int cols = clamp(request.getCols(), DEFAULT_COLS, MIN_COLS, MAX_COLS);
        int rows = clamp(request.getRows(), DEFAULT_ROWS, MIN_ROWS, MAX_ROWS);
        String shell = resolveShell();
        List<String> args = shellArgs(shell);
        Map<String, String> environment = environment();

        synchronized (createLock) {
            cleanupStale();
            if (sessions.size() >= maxSessions) {
                throw new GitOperationException(ErrorCode.TOO_MANY_TERMINALS,
                        "终端数量已达上限：" + maxSessions);
            }
            String terminalId = "term_" + UUID.randomUUID().toString().replace("-", "");
            TerminalSession session = new TerminalSession(
                    terminalId, repoId, shell, args, cwd, environment, cols, rows);
            sessions.put(terminalId, session);
            log.info("已创建终端会话 terminalId={} repoId={} shell={}", terminalId, repoId, shell);
            return session.toDto();
        }
    }

    /**
     * WebSocket 连接建立：启动 PTY 并开始读取输出。
     */
    public TerminalSessionDto attach(String terminalId, String ownerId, TerminalOutputSink sink) {
        requireAccess();
        TerminalSession session = requireSession(terminalId);
        synchronized (session) {
            // 并发 DELETE/断开可能已经把会话移出 map；不能在已回收的会话上启动进程
            if (session.isClosing() || sessions.get(terminalId) != session) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "终端不存在：" + terminalId);
            }
            TerminalProcess existing = session.getProcess();
            if (existing != null && existing.isAlive()) {
                throw new GitOperationException(ErrorCode.TERMINAL_BUSY, "终端已有活动连接");
            }
            if (session.getOwnerId() != null && !session.getOwnerId().equals(ownerId)) {
                throw new GitOperationException(ErrorCode.TERMINAL_BUSY, "终端已有活动连接");
            }
            session.setOwnerId(ownerId);
            TerminalLaunchRequest launch = new TerminalLaunchRequest(
                    session.getShell(), session.getArgs(), session.getCwd(),
                    session.getEnvironment(), session.getCols(), session.getRows());
            TerminalProcess process;
            try {
                process = processFactory.start(launch);
            } catch (IOException | LinkageError | RuntimeException e) {
                session.setOwnerId(null);
                log.warn("启动终端失败 terminalId={} shell={}", terminalId, session.getShell(), e);
                throw new GitOperationException(ErrorCode.TERMINAL_UNAVAILABLE,
                        "终端进程启动失败：" + nullToEmpty(e.getMessage()), null, e);
            }
            session.setProcess(process);
            session.setClosing(false);
            Thread reader = new Thread(new ReaderTask(session, process, sink),
                    "synclavis-terminal-" + terminalId);
            reader.setDaemon(true);
            session.setReaderThread(reader);
            reader.start();
            log.info("终端已连接 terminalId={} pid={}", terminalId, process.pid());
            return session.toDto();
        }
    }

    public void input(String terminalId, String data) {
        requireAccess();
        if (data == null) {
            return;
        }
        if (data.length() > MAX_INPUT_CHARS) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "终端输入过大");
        }
        TerminalSession session = requireSession(terminalId);
        synchronized (session) {
            TerminalProcess process = session.getProcess();
            if (process == null || !process.isAlive()) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "终端进程已结束");
            }
            try {
                Writer writer = new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(data);
                writer.flush();
            } catch (IOException e) {
                log.debug("写入终端失败 terminalId={}", terminalId, e);
                throw new GitOperationException(ErrorCode.NOT_FOUND, "终端连接已断开", null, e);
            }
        }
    }

    public void resize(String terminalId, int cols, int rows) {
        int safeCols = clamp(Integer.valueOf(cols), DEFAULT_COLS, MIN_COLS, MAX_COLS);
        int safeRows = clamp(Integer.valueOf(rows), DEFAULT_ROWS, MIN_ROWS, MAX_ROWS);
        TerminalSession session = requireSession(terminalId);
        synchronized (session) {
            session.setCols(safeCols);
            session.setRows(safeRows);
            TerminalProcess process = session.getProcess();
            if (process != null && process.isAlive()) {
                try {
                    process.resize(safeCols, safeRows);
                } catch (IOException e) {
                    log.debug("调整终端尺寸失败 terminalId={}", terminalId, e);
                }
            }
        }
    }

    public void detach(String terminalId, String ownerId) {
        TerminalSession session = sessions.get(terminalId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (ownerId != null && session.getOwnerId() != null
                    && !ownerId.equals(session.getOwnerId())) {
                return;
            }
        }
        close(terminalId);
    }

    public void delete(String terminalId) {
        close(terminalId);
    }

    /**
     * 插件启停后调用：不再有插件声明 terminal 权限时，终止所有终端。
     */
    public void revalidateAccess() {
        if (!hasAccess()) {
            closeAll("终端插件已停用");
        }
    }

    public int count() {
        return sessions.size();
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
        closeAll("应用关闭");
    }

    private void closeAll(String reason) {
        for (String terminalId : new ArrayList<String>(sessions.keySet())) {
            log.info("回收终端 terminalId={} reason={}", terminalId, reason);
            close(terminalId);
        }
    }

    private void close(String terminalId) {
        TerminalSession session = sessions.remove(terminalId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            session.setClosing(true);
            session.setOwnerId(null);
            TerminalProcess process = session.getProcess();
            session.setProcess(null);
            if (process != null) {
                process.destroy();
            }
            Thread reader = session.getReaderThread();
            if (reader != null && reader != Thread.currentThread()) {
                reader.interrupt();
            }
            session.setReaderThread(null);
        }
    }

    private TerminalSession requireSession(String terminalId) {
        TerminalSession session = terminalId == null ? null : sessions.get(terminalId);
        if (session == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "终端不存在：" + nullToEmpty(terminalId));
        }
        return session;
    }

    private void requireAccess() {
        if (!hasAccess()) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED,
                    "终端插件未启用或缺少 terminal 权限");
        }
    }

    private boolean hasAccess() {
        return enabled && plugins.hasEnabledPermission(TERMINAL_PERMISSION);
    }

    private void requireDirectory(String cwd) {
        try {
            Path path = Paths.get(cwd);
            if (!Files.isDirectory(path) || !Files.isReadable(path)) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "仓库目录不可读：" + cwd);
            }
        } catch (InvalidPathException e) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "仓库路径非法：" + cwd, null, e);
        }
    }

    private void cleanupStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TerminalSession> entry : sessions.entrySet()) {
            TerminalSession session = entry.getValue();
            if (session.getProcess() == null && now - session.getCreatedAt() > STALE_CREATED_MS) {
                sessions.remove(entry.getKey(), session);
                log.debug("清理未连接的终端会话 terminalId={}", entry.getKey());
            }
        }
    }

    private String resolveShell() {
        if (shellOverride != null) {
            return shellOverride;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String comspec = trimToNull(System.getenv("COMSPEC"));
            return comspec == null ? "powershell.exe" : comspec;
        }
        String shell = trimToNull(System.getenv("SHELL"));
        if (shell != null) {
            return shell;
        }
        if (Files.isExecutable(Paths.get("/bin/zsh"))) {
            return "/bin/zsh";
        }
        if (Files.isExecutable(Paths.get("/bin/bash"))) {
            return "/bin/bash";
        }
        return "/bin/sh";
    }

    private List<String> shellArgs(String shell) {
        List<String> args = new ArrayList<String>();
        String lower = shell == null ? "" : shell.toLowerCase();
        if (lower.endsWith("powershell.exe") || lower.endsWith("powershell")
                || lower.endsWith("pwsh.exe") || lower.endsWith("pwsh")) {
            args.add("-NoLogo");
        } else if (!lower.contains("cmd.exe") && !lower.contains("cmd")) {
            args.add("-i");
        }
        return args;
    }

    private Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<String, String>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        return env;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int raw = value == null ? fallback : value.intValue();
        return Math.max(min, Math.min(max, raw));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 读取 PTY 输出。读取结束后会话即失效，不能复用同一个 terminalId。
     */
    private final class ReaderTask implements Runnable {

        private final TerminalSession session;
        private final TerminalProcess process;
        private final TerminalOutputSink sink;

        private ReaderTask(TerminalSession session, TerminalProcess process,
                           TerminalOutputSink sink) {
            this.session = session;
            this.process = process;
            this.sink = sink;
        }

        @Override
        public void run() {
            try (Reader reader = new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    if (read > 0) {
                        sink.output(new String(buffer, 0, read));
                    }
                }
                int exitCode = process.waitFor();
                if (!session.isClosing()) {
                    sink.exit(exitCode);
                }
            } catch (IOException e) {
                log.debug("读取终端输出失败 terminalId={}", session.getTerminalId(), e);
                if (!session.isClosing()) {
                    sink.error("TERMINAL_ERROR", "终端输出读取失败");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.debug("终端输出回调异常 terminalId={}", session.getTerminalId(), e);
            } finally {
                // 读取线程结束时会话一定不可复用；若进程仍存活（回调异常等），顺手回收避免泄漏
                if (sessions.remove(session.getTerminalId(), session)) {
                    session.setClosing(true);
                    session.setOwnerId(null);
                    TerminalProcess current = session.getProcess();
                    session.setProcess(null);
                    if (current != null) {
                        current.destroy();
                    }
                }
            }
        }
    }
}
