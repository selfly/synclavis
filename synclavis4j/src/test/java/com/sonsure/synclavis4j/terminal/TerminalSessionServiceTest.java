package com.sonsure.synclavis4j.terminal;

import com.sonsure.synclavis4j.plugin.PluginService;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 终端会话生命周期（见《详细设计文档》§6.6）。
 */
class TerminalSessionServiceTest {

    @TempDir
    Path repoDir;

    private PluginService plugins;
    private RepoService repos;
    private FakeFactory factory;
    private TerminalSessionService service;

    @BeforeEach
    void setUp() {
        plugins = mock(PluginService.class);
        repos = mock(RepoService.class);
        factory = new FakeFactory();
        when(plugins.hasEnabledPermission("terminal")).thenReturn(true);
        when(repos.pathOf("repo-1")).thenReturn(repoDir.toString());
        service = new TerminalSessionService(plugins, repos, factory, true, "/bin/zsh", 2);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void websocketPathAcceptsOnlyExactTerminalId() {
        assertEquals("term_1",
                TerminalWebSocketHandler.resolveTerminalId("/ws/terminal/sessions/term_1"));
        assertNull(TerminalWebSocketHandler.resolveTerminalId("/ws/terminal/sessions/"));
        assertNull(TerminalWebSocketHandler.resolveTerminalId("/ws/terminal/sessions/term_1/input"));
        assertNull(TerminalWebSocketHandler.resolveTerminalId("/ws/other/term_1"));
    }

    @Test
    void createOnlyRegistersSession() {
        TerminalSessionDto dto = service.create(request(120, 40));

        assertNotNull(dto.getTerminalId());
        assertEquals("repo-1", dto.getRepoId());
        assertEquals("/bin/zsh", dto.getShell());
        assertEquals(repoDir.toString(), dto.getCwd());
        assertEquals(120, dto.getCols());
        assertEquals(40, dto.getRows());
        assertEquals(0, factory.started.get());
        assertEquals(1, service.count());
    }

    @Test
    void clampsTerminalSize() {
        TerminalSessionDto dto = service.create(request(9999, 0));

        assertEquals(500, dto.getCols());
        assertEquals(1, dto.getRows());
    }

    @Test
    void requiresEnabledPluginPermission() {
        when(plugins.hasEnabledPermission("terminal")).thenReturn(false);

        GitOperationException error = assertThrows(GitOperationException.class,
                () -> service.create(request(80, 24)));
        assertEquals(ErrorCode.PERMISSION_DENIED, error.getCode());
    }

    @Test
    void enforcesSessionLimit() {
        service.create(request(80, 24));
        service.create(request(80, 24));

        GitOperationException error = assertThrows(GitOperationException.class,
                () -> service.create(request(80, 24)));
        assertEquals(ErrorCode.TOO_MANY_TERMINALS, error.getCode());
    }

    @Test
    void attachStartsProcessAndRejectsSecondConnection() {
        TerminalSessionDto dto = service.create(request(100, 30));
        RecordingSink sink = new RecordingSink();

        service.attach(dto.getTerminalId(), "ws-1", sink);

        FakeProcess process = factory.last();
        assertEquals(1, factory.started.get());
        assertEquals(100, process.cols);
        assertEquals(30, process.rows);
        assertEquals(repoDir.toString(), process.request.getCwd());
        assertEquals("/bin/zsh", process.request.getShell());

        GitOperationException error = assertThrows(GitOperationException.class,
                () -> service.attach(dto.getTerminalId(), "ws-2", new RecordingSink()));
        assertEquals(ErrorCode.TERMINAL_BUSY, error.getCode());
    }

    @Test
    void inputAndResizeReachProcess() {
        TerminalSessionDto dto = service.create(request(80, 24));
        service.attach(dto.getTerminalId(), "ws-1", new RecordingSink());
        FakeProcess process = factory.last();

        service.input(dto.getTerminalId(), "echo hi\n");
        service.resize(dto.getTerminalId(), 140, 50);

        assertEquals("echo hi\n", new String(process.output.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(140, process.cols);
        assertEquals(50, process.rows);
    }

    @Test
    void detachTerminatesProcessAndRemovesSession() {
        TerminalSessionDto dto = service.create(request(80, 24));
        service.attach(dto.getTerminalId(), "ws-1", new RecordingSink());
        FakeProcess process = factory.last();

        service.detach(dto.getTerminalId(), "ws-1");

        assertTrue(process.destroyed.get() > 0);
        assertEquals(0, service.count());
    }

    @Test
    void processExitEmitsExitAndRemovesSession() throws Exception {
        factory.nextInput = "hello";
        factory.nextExitCode = 7;
        TerminalSessionDto dto = service.create(request(80, 24));
        RecordingSink sink = new RecordingSink();

        service.attach(dto.getTerminalId(), "ws-1", sink);

        long deadline = System.currentTimeMillis() + 3000;
        while (service.count() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("hello", sink.output.toString());
        assertEquals(Integer.valueOf(7), sink.exitCode.get());
        assertEquals(0, service.count());
    }

    @Test
    void revokePermissionClosesAllSessions() {
        TerminalSessionDto dto = service.create(request(80, 24));
        service.attach(dto.getTerminalId(), "ws-1", new RecordingSink());
        FakeProcess process = factory.last();

        when(plugins.hasEnabledPermission("terminal")).thenReturn(false);
        service.revalidateAccess();

        assertTrue(process.destroyed.get() > 0);
        assertEquals(0, service.count());
    }

    private CreateTerminalRequest request(int cols, int rows) {
        CreateTerminalRequest request = new CreateTerminalRequest();
        request.setRepoId("repo-1");
        request.setCols(Integer.valueOf(cols));
        request.setRows(Integer.valueOf(rows));
        return request;
    }

    private static final class RecordingSink implements TerminalOutputSink {

        private final StringBuilder output = new StringBuilder();
        private final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);

        @Override
        public void output(String data) {
            output.append(data);
        }

        @Override
        public void exit(int code) {
            exitCode.set(code);
        }

        @Override
        public void error(String code, String message) {
        }
    }

    private static final class FakeFactory implements TerminalProcessFactory {

        private final AtomicInteger started = new AtomicInteger();
        private final List<FakeProcess> processes = new ArrayList<FakeProcess>();
        private FakeProcess current;
        private String nextInput = "";
        private int nextExitCode;

        @Override
        public TerminalProcess start(TerminalLaunchRequest request) throws IOException {
            started.incrementAndGet();
            current = new FakeProcess(request, nextInput, nextExitCode);
            processes.add(current);
            return current;
        }

        private FakeProcess last() {
            return current;
        }
    }

    private static final class FakeProcess implements TerminalProcess {

        private final TerminalLaunchRequest request;
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicInteger destroyed = new AtomicInteger();
        private int cols;
        private int rows;
        private final int exitCode;

        private FakeProcess(TerminalLaunchRequest request, String input, int exitCode) {
            this.request = request;
            this.input = input.isEmpty()
                    ? new BlockingInputStream()
                    : new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            this.cols = request.getCols();
            this.rows = request.getRows();
            this.exitCode = exitCode;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void resize(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
        }

        @Override
        public boolean isAlive() {
            return destroyed.get() == 0;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.incrementAndGet();
            try {
                input.close();
            } catch (IOException ignore) {
                // 测试用假进程
            }
        }

        @Override
        public long pid() {
            return 42L;
        }
    }

    private static final class BlockingInputStream extends InputStream {

        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            synchronized (this) {
                while (!closed) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", e);
                    }
                }
            }
            return -1;
        }

        @Override
        public void close() {
            closed = true;
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
