package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.state.PluginSettings;
import com.sonsure.synclavis4j.state.StateStore;
import java.io.File;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * AI 会话与 turn 编排（见《详细设计文档》§6.5）。
 *
 * <p>该服务只依赖 harness 无关的 SPI，负责：
 * <ul>
 *   <li>按插件选择 harness，维护不透明 {@code sessionRef} 到原生句柄的映射；</li>
 *   <li>串行化同一会话的 turn，并把 provider 事件盖章为统一 {@link AiEvent}；</li>
 *   <li>保证每轮以 {@code turn.completed} 收尾，失败时先发 {@code error}；</li>
 *   <li>权限确认路由、事件内存缓冲与取消。</li>
 * </ul>
 */
@Service
public class AiSessionService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionService.class);

    private static final int EVENT_BUFFER_LIMIT = 1000;
    private static final int DEFAULT_EVENT_LIMIT = 200;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final HarnessRegistry harnesses;
    private final AiRepoPathResolver repoPaths;
    private final StateStore stateStore;
    private final AiEventBroadcaster broadcaster;
    private final Executor executor;
    private final Map<String, SessionRuntime> sessions =
            new ConcurrentHashMap<String, SessionRuntime>();

    public AiSessionService(HarnessRegistry harnesses,
                            AiRepoPathResolver repoPaths,
                            StateStore stateStore,
                            AiEventBroadcaster broadcaster,
                            @Qualifier(AiConfig.AI_TURN_EXECUTOR) Executor executor) {
        this.harnesses = harnesses;
        this.repoPaths = repoPaths;
        this.stateStore = stateStore;
        this.broadcaster = broadcaster;
        this.executor = executor;
    }

    /** harness 列表 + 每个插件当前选择的 harness。 */
    public HarnessListDto harnesses() {
        HarnessListDto dto = new HarnessListDto();
        dto.setHarnesses(harnesses.descriptors());
        dto.setActiveHarness(activeHarness());
        return dto;
    }

    public Map<String, String> activeHarness() {
        return new LinkedHashMap<String, String>(
                stateStore.load().getGlobalSettings().getPlugins().getActiveHarness());
    }

    /**
     * 记录某个插件选择的 harness。
     *
     * <p>只校验 harness 是否存在，不校验插件是否已安装：插件可能尚未加载，
     * 但用户可以先保存选择；真正创建会话时再校验 available。</p>
     */
    public synchronized Map<String, String> setActiveHarness(String pluginId, String harnessId) {
        String plugin = trimToNull(pluginId);
        String harness = trimToNull(harnessId);
        if (plugin == null || harness == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "pluginId 与 harnessId 不能为空");
        }
        if (harnesses.descriptor(harness) == null) {
            throw new GitOperationException(ErrorCode.HARNESS_NOT_FOUND, "AI harness 不存在: " + harness);
        }
        PluginSettings settings = stateStore.load().getGlobalSettings().getPlugins();
        settings.getActiveHarness().put(plugin, harness);
        stateStore.updateGlobalSettings(stateStore.load().getGlobalSettings());
        return activeHarness();
    }

    public AiSessionDto createSession(CreateAiSessionRequest request) {
        if (request == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        String pluginId = trimToNull(request.getPluginId());
        HarnessProvider provider = selectProvider(trimToNull(request.getHarnessId()), pluginId);
        HarnessDescriptor descriptor = provider.descriptor();
        if (descriptor == null || !descriptor.isAvailable()) {
            String reason = descriptor == null ? "harness 不可用" : descriptor.getReason();
            throw new GitOperationException(ErrorCode.HARNESS_UNAVAILABLE,
                    reason == null || reason.isEmpty() ? "AI harness 不可用" : reason);
        }

        String repoId = trimToNull(request.getRepoId());
        String repoPath = repoId == null ? null : repoPaths.pathOf(repoId);
        String title = trimToNull(request.getTitle());
        if (title == null) {
            title = defaultTitle(repoPath);
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }

        String sessionRef = "ai_" + UUID.randomUUID().toString().replace("-", "");
        HarnessSessionRequest harnessRequest = new HarnessSessionRequest();
        harnessRequest.setHostSessionRef(sessionRef);
        harnessRequest.setPluginId(pluginId);
        harnessRequest.setRepoId(repoId);
        harnessRequest.setRepoPath(repoPath);
        harnessRequest.setTitle(title);

        HarnessSessionHandle handle;
        try {
            handle = provider.createSession(harnessRequest);
        } catch (HarnessException e) {
            throw new GitOperationException(ErrorCode.HARNESS_ERROR,
                    messageOf(e, "AI harness 创建会话失败"), e.getDetails(), e);
        }
        if (handle == null || isBlank(handle.getNativeRef())) {
            throw new GitOperationException(ErrorCode.HARNESS_ERROR, "AI harness 返回了空会话句柄");
        }

        String now = now();
        AiSession session = new AiSession();
        session.setSessionRef(sessionRef);
        session.setHarnessId(descriptor.getId());
        session.setPluginId(pluginId);
        session.setRepoId(repoId);
        session.setTitle(title);
        session.setStatus("idle");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setLastSeq(0L);

        sessions.put(sessionRef, new SessionRuntime(provider, handle, session));
        return AiSessionDto.from(session);
    }

    public AiSessionListDto listSessions(String repoId) {
        String filter = trimToNull(repoId);
        List<AiSessionDto> result = new ArrayList<AiSessionDto>();
        for (SessionRuntime runtime : sessions.values()) {
            AiSessionDto dto = runtime.dto();
            if (filter == null || filter.equals(dto.getRepoId())) {
                result.add(dto);
            }
        }
        Collections.sort(result, new Comparator<AiSessionDto>() {
            @Override
            public int compare(AiSessionDto left, AiSessionDto right) {
                int byTime = right.getCreatedAt().compareTo(left.getCreatedAt());
                return byTime != 0 ? byTime : left.getSessionRef().compareTo(right.getSessionRef());
            }
        });
        AiSessionListDto dto = new AiSessionListDto();
        dto.setSessions(result);
        return dto;
    }

    public AiSessionDto getSession(String sessionRef) {
        return require(sessionRef).dto();
    }

    public void deleteSession(String sessionRef) {
        SessionRuntime runtime = require(sessionRef);
        cancelRuntime(runtime, null);
        synchronized (runtime.lock) {
            runtime.closed = true;
        }
        try {
            runtime.provider.closeSession(runtime.handle);
        } catch (RuntimeException e) {
            log.warn("关闭 AI harness 会话失败: {}", sessionRef, e);
        }
        sessions.remove(sessionRef);
    }

    /**
     * 提交一轮 prompt。立即返回 turnId，实际执行在 AI 线程池中，事件走 WebSocket。
     */
    public AiPromptResultDto prompt(String sessionRef, AiPromptRequest request) {
        SessionRuntime runtime = require(sessionRef);
        if (request == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "请求体不能为空");
        }
        String text = trimToNull(request.getText());
        if (text == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        HarnessDescriptor descriptor = runtime.provider.descriptor();
        if (descriptor == null || !descriptor.getCapabilities().isPrompt()) {
            throw new GitOperationException(ErrorCode.UNSUPPORTED, "当前 harness 不支持 prompt");
        }

        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "");
        synchronized (runtime.lock) {
            if (runtime.closed) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "AI 会话不存在: " + sessionRef);
            }
            if (runtime.cancelling) {
                throw new GitOperationException(ErrorCode.AI_BUSY, "AI 会话正在取消，请稍后重试");
            }
            if (runtime.session.getCurrentTurnId() != null) {
                throw new GitOperationException(ErrorCode.AI_BUSY, "AI 会话正在执行，请先取消或等待完成");
            }
            runtime.session.setCurrentTurnId(turnId);
            runtime.session.setStatus("busy");
            runtime.session.setUpdatedAt(now());
            runtime.session.setLastError(null);
        }

        Map<String, Object> started = payload("model", trimToNull(request.getModel()));
        // 面板回放历史时需要用户侧 prompt；事件缓冲只在内存中，不落盘
        started.put("text", text);
        publish(runtime, turnId, AiEventTypes.TURN_STARTED, started);

        HarnessPrompt prompt = new HarnessPrompt();
        prompt.setText(text);
        prompt.setModel(trimToNull(request.getModel()));
        prompt.setMetadata(new LinkedHashMap<String, Object>(request.getMetadata()));

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runTurn(runtime, turnId, prompt);
                }
            });
        } catch (RuntimeException e) {
            finishTurn(runtime, turnId, false,
                    new HarnessException(HarnessException.HARNESS_ERROR, "AI 执行线程池不可用", e));
            throw new GitOperationException(ErrorCode.HARNESS_ERROR, "AI 执行线程池不可用", null, e);
        }
        return new AiPromptResultDto(sessionRef, turnId);
    }

    /** 取消指定 turn（{@code turnId} 为空时取消当前 turn）。 */
    public boolean cancel(String sessionRef, String turnId) {
        return cancelRuntime(require(sessionRef), turnId);
    }

    public AiPermissionResultDto respondPermission(String sessionRef, String requestId,
                                                   AiPermissionRequest request) {
        SessionRuntime runtime = require(sessionRef);
        String id = trimToNull(requestId);
        if (id == null) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "requestId 不能为空");
        }
        String decision = trimToNull(request == null ? null : request.getDecision());
        decision = decision == null ? null : decision.toLowerCase(Locale.ROOT);
        if (!"allow".equals(decision) && !"deny".equals(decision)) {
            throw new GitOperationException(ErrorCode.INVALID_PARAM, "decision 只能是 allow 或 deny");
        }
        HarnessDescriptor descriptor = runtime.provider.descriptor();
        if (descriptor == null || !descriptor.getCapabilities().isPermissions()) {
            throw new GitOperationException(ErrorCode.UNSUPPORTED, "当前 harness 不支持权限确认");
        }
        try {
            runtime.provider.respondPermission(runtime.handle, id, decision);
        } catch (HarnessException e) {
            throw new GitOperationException(ErrorCode.HARNESS_ERROR,
                    messageOf(e, "权限确认失败"), e.getDetails(), e);
        }

        String currentTurn;
        synchronized (runtime.lock) {
            if (runtime.closed) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "AI 会话不存在: " + sessionRef);
            }
            currentTurn = runtime.session.getCurrentTurnId();
        }
        if (currentTurn != null) {
            Map<String, Object> payload = payload("requestId", id);
            payload.put("decision", decision);
            publish(runtime, currentTurn, AiEventTypes.PERMISSION_RESOLVED, payload);
        }
        return new AiPermissionResultDto(id, decision);
    }

    /**
     * 事件回放：只返回内存缓冲中 {@code afterSeq} 之后的事件。
     */
    public AiEventsDto events(String sessionRef, long afterSeq, int limit) {
        SessionRuntime runtime = require(sessionRef);
        long from = Math.max(0L, afterSeq);
        int max = limit <= 0 ? DEFAULT_EVENT_LIMIT : Math.min(limit, EVENT_BUFFER_LIMIT);
        List<AiEvent> result = new ArrayList<AiEvent>();
        long lastSeq;
        boolean truncated = false;
        synchronized (runtime.lock) {
            lastSeq = runtime.session.getLastSeq();
            if (!runtime.events.isEmpty()
                    && runtime.events.get(0).getSeq() > from + 1) {
                truncated = true;
            }
            if (runtime.events.isEmpty() && from < lastSeq) {
                truncated = true;
            }
            for (AiEvent event : runtime.events) {
                if (event.getSeq() <= from) {
                    continue;
                }
                if (result.size() >= max) {
                    truncated = true;
                    break;
                }
                result.add(event);
            }
        }
        return new AiEventsDto(sessionRef, lastSeq, truncated, result);
    }

    @PreDestroy
    public void shutdown() {
        for (SessionRuntime runtime : new ArrayList<SessionRuntime>(sessions.values())) {
            try {
                cancelRuntime(runtime, null);
            } catch (RuntimeException e) {
                log.debug("关闭时取消 AI turn 失败", e);
            }
            try {
                runtime.provider.closeSession(runtime.handle);
            } catch (RuntimeException e) {
                log.debug("关闭时释放 AI harness 会话失败", e);
            }
        }
        sessions.clear();
    }

    private HarnessProvider selectProvider(String harnessId, String pluginId) {
        String selected = harnessId;
        if (selected == null && pluginId != null) {
            selected = activeHarness().get(pluginId);
        }
        if (selected != null) {
            HarnessProvider provider = harnesses.provider(selected);
            if (provider == null) {
                throw new GitOperationException(ErrorCode.HARNESS_NOT_FOUND,
                        "AI harness 不存在: " + selected);
            }
            HarnessDescriptor descriptor = provider.descriptor();
            if (descriptor == null || !descriptor.isAvailable()) {
                String reason = descriptor == null ? null : descriptor.getReason();
                throw new GitOperationException(ErrorCode.HARNESS_UNAVAILABLE,
                        reason == null || reason.isEmpty()
                                ? "AI harness 不可用: " + selected : reason);
            }
            return provider;
        }
        HarnessProvider provider = harnesses.firstAvailable();
        if (provider == null) {
            throw new GitOperationException(ErrorCode.HARNESS_UNAVAILABLE, "没有可用的 AI harness");
        }
        return provider;
    }

    private void runTurn(SessionRuntime runtime, String turnId, HarnessPrompt prompt) {
        try {
            runtime.provider.prompt(runtime.handle, prompt, new SessionEventSink(runtime, turnId));
            // provider 可能忽略取消正常返回；取消请求优先，不能把它记成成功
            if (isCancelling(runtime, turnId)) {
                finishTurn(runtime, turnId, false, HarnessException.cancelled());
            } else {
                finishTurn(runtime, turnId, true, null);
            }
        } catch (HarnessException e) {
            finishTurn(runtime, turnId, false, e);
        } catch (Throwable e) {
            log.error("AI harness 执行异常 session={} turn={}", runtime.session.getSessionRef(), turnId, e);
            finishTurn(runtime, turnId, false,
                    new HarnessException(HarnessException.HARNESS_ERROR,
                            messageOf(e, "AI harness 执行失败")));
            if (e instanceof Error) {
                throw (Error) e;
            }
        }
    }

    /**
     * 结束一轮 turn。以 {@code currentTurnId} 做幂等：取消与正常完成并发时只有一方生效。
     */
    private boolean finishTurn(SessionRuntime runtime, String turnId, boolean ok,
                               HarnessException error) {
        List<AiEvent> pending = new ArrayList<AiEvent>();
        synchronized (runtime.lock) {
            if (runtime.closed || !turnId.equals(runtime.session.getCurrentTurnId())) {
                return false;
            }
            if (ok) {
                runtime.session.setLastError(null);
            } else {
                String code = error == null ? HarnessException.HARNESS_ERROR : error.getCode();
                String message = messageOf(error, "AI harness 执行失败");
                runtime.session.setLastError(lastError(code, message));
                pending.add(appendLocked(runtime, turnId, AiEventTypes.ERROR,
                        errorPayload(code, message, error == null ? null : error.getDetails())));
            }
            runtime.session.setStatus("idle");
            runtime.session.setCurrentTurnId(null);
            runtime.session.setUpdatedAt(now());
            pending.add(appendLocked(runtime, turnId, AiEventTypes.TURN_COMPLETED,
                    payload("ok", Boolean.valueOf(ok))));
            // 在会话锁内广播，保证 turn.completed 与下一轮 turn.started 不会在 WS 上乱序
            broadcast(pending);
        }
        return true;
    }

    private boolean cancelRuntime(SessionRuntime runtime, String turnId) {
        String current;
        synchronized (runtime.lock) {
            if (runtime.closed) {
                return false;
            }
            current = runtime.session.getCurrentTurnId();
            if (current == null) {
                return false;
            }
            if (turnId != null && !turnId.equals(current)) {
                return false;
            }
            // 从发出取消到 provider.cancel 返回之间禁止新 turn，避免取消误伤下一轮
            runtime.cancelling = true;
        }
        try {
            try {
                runtime.provider.cancel(runtime.handle);
            } catch (RuntimeException e) {
                log.debug("AI harness 取消失败 session={}", runtime.session.getSessionRef(), e);
            }
            boolean finished = finishTurn(runtime, current, false, HarnessException.cancelled());
            if (finished) {
                return true;
            }
            // prompt 线程可能先一步因 cancelling 收尾；只要结果是 CANCELLED，本次取消仍然算生效
            synchronized (runtime.lock) {
                Map<String, String> lastError = runtime.session.getLastError();
                return lastError != null
                        && HarnessException.CANCELLED.equals(lastError.get("code"));
            }
        } finally {
            synchronized (runtime.lock) {
                runtime.cancelling = false;
            }
        }
    }

    private boolean isCancelling(SessionRuntime runtime, String turnId) {
        synchronized (runtime.lock) {
            return runtime.cancelling || runtime.closed
                    || !turnId.equals(runtime.session.getCurrentTurnId());
        }
    }

    private void publish(SessionRuntime runtime, String turnId, String type,
                         Map<String, Object> payload) {
        if (isBlank(type)) {
            return;
        }
        synchronized (runtime.lock) {
            if (runtime.closed || turnId == null
                    || !turnId.equals(runtime.session.getCurrentTurnId())) {
                return;
            }
            AiEvent event = appendLocked(runtime, turnId, type, payload);
            // 在会话锁内广播，保证 seq 与 WS 投递顺序一致
            broadcast(Collections.singletonList(event));
        }
    }

    private AiEvent appendLocked(SessionRuntime runtime, String turnId, String type,
                                 Map<String, Object> payload) {
        AiEvent event = new AiEvent(type, runtime.session.getSessionRef(), turnId,
                runtime.nextSeq++, now(), payload);
        runtime.events.add(event);
        while (runtime.events.size() > EVENT_BUFFER_LIMIT) {
            runtime.events.remove(0);
        }
        runtime.session.setLastSeq(event.getSeq());
        return event;
    }

    private void broadcast(List<AiEvent> events) {
        for (AiEvent event : events) {
            try {
                broadcaster.broadcast(event);
            } catch (RuntimeException e) {
                log.warn("AI 事件广播失败 session={} seq={}",
                        event.getSessionRef(), event.getSeq(), e);
            }
        }
    }

    private SessionRuntime require(String sessionRef) {
        SessionRuntime runtime = sessionRef == null ? null : sessions.get(sessionRef);
        if (runtime == null) {
            throw new GitOperationException(ErrorCode.NOT_FOUND, "AI 会话不存在: " + sessionRef);
        }
        synchronized (runtime.lock) {
            if (runtime.closed) {
                throw new GitOperationException(ErrorCode.NOT_FOUND, "AI 会话不存在: " + sessionRef);
            }
        }
        return runtime;
    }

    private String defaultTitle(String repoPath) {
        if (repoPath == null) {
            return "AI 会话";
        }
        String name = new File(repoPath).getName();
        return name == null || name.isEmpty() ? "AI 会话" : name + " · AI";
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }

    private static Map<String, Object> errorPayload(String code, String message,
                                                    Map<String, Object> details) {
        Map<String, Object> payload = payload("code", code);
        payload.put("message", message);
        if (details != null && !details.isEmpty()) {
            payload.put("details", details);
        }
        return payload;
    }

    private static Map<String, String> lastError(String code, String message) {
        Map<String, String> error = new LinkedHashMap<String, String>();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    private static String now() {
        return OffsetDateTime.now().format(TIME_FORMAT);
    }

    private static String messageOf(Throwable error, String fallback) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return fallback;
        }
        return error.getMessage();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** provider 事件出口；每个 turn 一个实例，防止跨轮串事件。 */
    private final class SessionEventSink implements HarnessEventSink {

        private final SessionRuntime runtime;
        private final String turnId;

        private SessionEventSink(SessionRuntime runtime, String turnId) {
            this.runtime = runtime;
            this.turnId = turnId;
        }

        @Override
        public void emit(String type, Map<String, Object> payload) {
            publish(runtime, turnId, type, payload);
        }

        @Override
        public boolean isCancelled() {
            synchronized (runtime.lock) {
                return runtime.cancelling || runtime.closed
                        || !turnId.equals(runtime.session.getCurrentTurnId());
            }
        }
    }

    private static final class SessionRuntime {

        private final HarnessProvider provider;
        private final HarnessSessionHandle handle;
        private final AiSession session;
        private final Object lock = new Object();
        private final List<AiEvent> events = new ArrayList<AiEvent>();
        private long nextSeq = 1L;
        private boolean closed;
        private boolean cancelling;

        private SessionRuntime(HarnessProvider provider, HarnessSessionHandle handle, AiSession session) {
            this.provider = provider;
            this.handle = handle;
            this.session = session;
        }

        private AiSessionDto dto() {
            synchronized (lock) {
                return AiSessionDto.from(session);
            }
        }
    }
}
