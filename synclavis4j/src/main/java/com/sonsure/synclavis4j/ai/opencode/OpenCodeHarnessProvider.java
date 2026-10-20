package com.sonsure.synclavis4j.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.ai.HarnessCapabilities;
import com.sonsure.synclavis4j.ai.HarnessDescriptor;
import com.sonsure.synclavis4j.ai.HarnessEventSink;
import com.sonsure.synclavis4j.ai.HarnessException;
import com.sonsure.synclavis4j.ai.HarnessPrompt;
import com.sonsure.synclavis4j.ai.HarnessProvider;
import com.sonsure.synclavis4j.ai.HarnessSessionHandle;
import com.sonsure.synclavis4j.ai.HarnessSessionRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenCode harness 适配器（见《详细设计文档》§6.5）。
 *
 * <p>该适配器把 OpenCode 的会话、SSE 事件、权限请求和 diff 归一化为 ai-core 事件。
 * OpenCode 的地址与密码只由后端发现和持有；前端只看到 harness 无关的 {@code /api/ai/**}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "synclavis.ai.opencode", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpenCodeHarnessProvider implements HarnessProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeHarnessProvider.class);

    static final String HARNESS_ID = "opencode";
    private static final String HARNESS_NAME = "OpenCode";
    private static final long EVENT_CLOCK_SLACK_MILLIS = 2000L;
    private static final HarnessCapabilities CAPABILITIES = capabilities();

    private final OpenCodeClient client;
    private final ObjectMapper mapper;
    private final Object streamLock = new Object();
    private final Map<String, TurnWaiter> waiters = new ConcurrentHashMap<String, TurnWaiter>();

    private volatile OpenCodeEventStream stream;

    @Autowired
    public OpenCodeHarnessProvider(ObjectMapper mapper,
                                   @Value("${synclavis.ai.opencode.service-file:}")
                                   String serviceFile,
                                   @Value("${synclavis.ai.opencode.timeout-ms:10000}")
                                   long timeoutMillis) {
        this(new OpenCodeClient(new OpenCodeDiscovery(mapper, serviceFile, timeoutMillis),
                mapper, timeoutMillis), mapper);
    }

    OpenCodeHarnessProvider(OpenCodeClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public HarnessDescriptor descriptor() {
        OpenCodeDiscovery.Result result;
        try {
            result = client.discovery();
        } catch (RuntimeException e) {
            log.debug("读取 OpenCode 状态失败", e);
            return new HarnessDescriptor(HARNESS_ID, HARNESS_NAME, null, false,
                    "无法读取 OpenCode 状态", CAPABILITIES);
        }
        if (result == null || !result.isAvailable()) {
            String reason = result == null ? null : result.getReason();
            return new HarnessDescriptor(HARNESS_ID, HARNESS_NAME, null, false,
                    reason == null ? "OpenCode 不可用" : reason, CAPABILITIES);
        }
        return new HarnessDescriptor(HARNESS_ID, HARNESS_NAME,
                result.getEndpoint().getVersion(), true, null, CAPABILITIES);
    }

    @Override
    public HarnessSessionHandle createSession(HarnessSessionRequest request) {
        if (request == null) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, "创建会话参数不能为空");
        }
        String directory = trimToNull(request.getRepoPath());
        if (directory == null) {
            directory = System.getProperty("user.home");
        }
        String title = trimToNull(request.getTitle());
        if (title == null) {
            title = "AI 会话";
        }
        try {
            String nativeRef = client.createSession(title, directory);
            HarnessSessionHandle handle = new HarnessSessionHandle(nativeRef);
            handle.getAttributes().put("directory", directory);
            return handle;
        } catch (OpenCodeException e) {
            throw map(e, "创建 OpenCode 会话失败");
        } catch (IllegalArgumentException e) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, e.getMessage(), e);
        }
    }

    @Override
    public void prompt(HarnessSessionHandle handle, HarnessPrompt prompt, HarnessEventSink sink) {
        if (handle == null || isBlank(handle.getNativeRef())) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, "OpenCode 会话句柄无效");
        }
        if (prompt == null || isBlank(prompt.getText())) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, "prompt 不能为空");
        }
        String nativeRef = handle.getNativeRef();
        ensureStream();

        long requestedAt = System.currentTimeMillis();
        TurnWaiter waiter = new TurnWaiter(nativeRef, sink, requestedAt);
        TurnWaiter previous = waiters.put(nativeRef, waiter);
        if (previous != null) {
            waiters.put(nativeRef, previous);
            throw new HarnessException(HarnessException.HARNESS_ERROR,
                    "OpenCode 会话正在执行");
        }

        try {
            if (!isBlank(prompt.getModel())) {
                client.switchModel(nativeRef, prompt.getModel());
            }
            client.prompt(nativeRef, prompt.getText(), prompt.getMetadata());
            try {
                waiter.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw HarnessException.cancelled();
            }

            HarnessException failure = waiter.failure();
            if (failure != null) {
                throw failure;
            }
            if (sink.isCancelled()) {
                throw HarnessException.cancelled();
            }
            sink.messageCompleted(waiter.text());
            emitDiff(nativeRef, sink);
        } catch (OpenCodeException e) {
            throw map(e, "OpenCode 执行失败");
        } catch (IllegalArgumentException e) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, e.getMessage(), e);
        } finally {
            waiters.remove(nativeRef, waiter);
        }
    }

    @Override
    public void cancel(HarnessSessionHandle handle) {
        if (handle == null || isBlank(handle.getNativeRef())) {
            return;
        }
        String nativeRef = handle.getNativeRef();
        TurnWaiter waiter = waiters.get(nativeRef);
        // 先让 prompt 线程收敛，再尽量通知 OpenCode；HTTP 失败不能把用户取消变成成功
        if (waiter != null) {
            waiter.cancelByHost();
        }
        try {
            client.interrupt(nativeRef);
        } catch (OpenCodeException e) {
            log.debug("中断 OpenCode 会话失败: {}", nativeRef, e);
        } catch (IllegalArgumentException e) {
            log.debug("OpenCode 会话 id 非法: {}", nativeRef, e);
        }
    }

    @Override
    public void respondPermission(HarnessSessionHandle handle, String requestId, String decision) {
        if (handle == null || isBlank(handle.getNativeRef())) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, "OpenCode 会话句柄无效");
        }
        String reply;
        if ("allow".equalsIgnoreCase(decision)) {
            reply = "once";
        } else if ("deny".equalsIgnoreCase(decision)) {
            reply = "reject";
        } else {
            throw HarnessException.unsupported("decision 只能是 allow 或 deny");
        }
        try {
            client.replyPermission(handle.getNativeRef(), requestId, reply);
        } catch (OpenCodeException e) {
            throw map(e, "OpenCode 权限确认失败");
        } catch (IllegalArgumentException e) {
            throw new HarnessException(HarnessException.HARNESS_ERROR, e.getMessage(), e);
        }
    }

    @Override
    public void closeSession(HarnessSessionHandle handle) {
        if (handle == null || isBlank(handle.getNativeRef())) {
            return;
        }
        String nativeRef = handle.getNativeRef();
        TurnWaiter waiter = waiters.get(nativeRef);
        if (waiter != null) {
            waiter.cancelByHost();
        }
        try {
            client.deleteSession(nativeRef);
        } catch (OpenCodeException e) {
            if (e.getStatus() != 404) {
                log.debug("删除 OpenCode 会话失败: {}", nativeRef, e);
            }
        } catch (IllegalArgumentException e) {
            log.debug("OpenCode 会话 id 非法: {}", nativeRef, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        OpenCodeEventStream current;
        synchronized (streamLock) {
            current = stream;
            stream = null;
            for (TurnWaiter waiter : waiters.values()) {
                waiter.cancelByHost();
            }
        }
        waiters.clear();
        if (current != null) {
            current.close();
        }
    }

    private void ensureStream() {
        synchronized (streamLock) {
            if (stream != null && !stream.isClosed()) {
                return;
            }
            try {
                stream = client.openEventStream(new OpenCodeEventStream.Listener() {
                    @Override
                    public void onEvent(JsonNode event) {
                        dispatch(event);
                    }

                    @Override
                    public void onClosed(OpenCodeEventStream source, Throwable error) {
                        handleStreamClosed(source, error);
                    }
                });
            } catch (OpenCodeException e) {
                throw map(e, "无法连接 OpenCode 事件流");
            }
        }
    }

    private void handleStreamClosed(OpenCodeEventStream source, Throwable error) {
        synchronized (streamLock) {
            if (stream != source) {
                return;
            }
            stream = null;
            for (TurnWaiter waiter : waiters.values()) {
                waiter.fail(error);
            }
        }
    }

    private void dispatch(JsonNode event) {
        if (event == null) {
            return;
        }
        String type = OpenCodeHttp.text(event, "type");
        JsonNode data = event.get("data");
        String sessionId = OpenCodeHttp.text(data, "sessionID");
        if (type == null || sessionId == null) {
            return;
        }
        TurnWaiter waiter = waiters.get(sessionId);
        if (waiter != null) {
            waiter.onEvent(type, event, data);
        }
    }

    private void emitDiff(String nativeRef, HarnessEventSink sink) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            List<Map<String, Object>> files = client.diff(nativeRef);
            if (!files.isEmpty() && !sink.isCancelled()) {
                sink.diffUpdated(files);
            }
        } catch (OpenCodeException e) {
            log.debug("读取 OpenCode diff 失败: {}", nativeRef, e);
        } catch (IllegalArgumentException e) {
            log.debug("OpenCode 会话 id 非法: {}", nativeRef, e);
        }
    }

    private HarnessException map(OpenCodeException error, String fallback) {
        String message = error == null ? null : error.getMessage();
        if (isBlank(message)) {
            message = fallback;
        }
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        if (error != null && error.getStatus() > 0) {
            details.put("status", Integer.valueOf(error.getStatus()));
        }
        return new HarnessException(HarnessException.HARNESS_ERROR, message, details, error);
    }

    private static HarnessCapabilities capabilities() {
        HarnessCapabilities capabilities = new HarnessCapabilities();
        capabilities.setPrompt(true);
        capabilities.setStreaming(true);
        capabilities.setCancel(true);
        capabilities.setResume(false);
        capabilities.setPermissions(true);
        capabilities.setDiff(true);
        capabilities.setTools(true);
        return capabilities;
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

    private static Object toJava(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(node, Object.class);
        } catch (IllegalArgumentException e) {
            return node.toString();
        }
    }

    private static String errorText(JsonNode node) {
        String message = OpenCodeHttp.text(node, "message");
        if (message != null) {
            return message;
        }
        String type = OpenCodeHttp.text(node, "type");
        return type == null ? "OpenCode 工具调用失败" : type;
    }

    private static OpenCodeError parseError(JsonNode node) {
        if (node == null || node.isNull()) {
            return new OpenCodeError(null, "OpenCode 执行失败", 0);
        }
        String type = OpenCodeHttp.text(node, "type");
        String message = OpenCodeHttp.text(node, "message");
        if (message == null) {
            message = type == null ? "OpenCode 执行失败" : type;
        }
        return new OpenCodeError(type, message, node.path("status").asInt(0));
    }

    /**
     * 单个 OpenCode turn 的等待器；只在 SSE 线程和 prompt 线程之间共享。
     */
    private final class TurnWaiter {

        private final String nativeRef;
        private final HarnessEventSink sink;
        private final long requestedAt;
        private final CountDownLatch done = new CountDownLatch(1);
        private final StringBuilder text = new StringBuilder();
        private final Set<String> textDeltas = new HashSet<String>();
        private final Map<String, String> toolNames = new HashMap<String, String>();
        private final Set<String> startedTools = new HashSet<String>();

        private OpenCodeError error;
        private Throwable streamError;
        private boolean interrupted;
        private boolean finished;

        private TurnWaiter(String nativeRef, HarnessEventSink sink, long requestedAt) {
            this.nativeRef = nativeRef;
            this.sink = sink;
            this.requestedAt = requestedAt;
        }

        private synchronized void onEvent(String type, JsonNode root, JsonNode data) {
            if (finished) {
                return;
            }
            long created = root.path("created").asLong(0L);
            if (created > 0L && requestedAt > 0L
                    && created < requestedAt - EVENT_CLOCK_SLACK_MILLIS) {
                return;
            }
            if (sink.isCancelled()) {
                interrupted = true;
                finishLocked();
                return;
            }
            if ("session.text.delta".equals(type)) {
                String delta = OpenCodeHttp.rawText(data, "delta");
                if (delta != null) {
                    text.append(delta);
                    textDeltas.add(textKey(data));
                    sink.messageDelta(delta);
                }
            } else if ("session.text.ended".equals(type)) {
                String ended = OpenCodeHttp.rawText(data, "text");
                String key = textKey(data);
                if (ended != null && !textDeltas.contains(key)) {
                    text.append(ended);
                    sink.messageDelta(ended);
                }
            } else if ("session.tool.input.started".equals(type)) {
                String id = OpenCodeHttp.text(data, "id");
                String name = OpenCodeHttp.text(data, "name");
                if (id != null && name != null) {
                    toolNames.put(id, name);
                }
            } else if ("session.tool.called".equals(type)) {
                String id = OpenCodeHttp.text(data, "id");
                if (id != null) {
                    String name = toolName(id);
                    if (startedTools.add(id)) {
                        sink.toolStarted(id, name, toJava(mapper, data.get("input")));
                    }
                }
            } else if ("session.tool.success".equals(type)) {
                String id = OpenCodeHttp.text(data, "id");
                if (id != null) {
                    String name = toolName(id);
                    ensureToolStarted(id, name);
                    sink.toolCompleted(id, name, true,
                            toJava(mapper, data.get("content")), null);
                }
            } else if ("session.tool.failed".equals(type)) {
                String id = OpenCodeHttp.text(data, "id");
                if (id != null) {
                    String name = toolName(id);
                    ensureToolStarted(id, name);
                    sink.toolCompleted(id, name, false,
                            toJava(mapper, data.get("content")),
                            errorText(data.get("error")));
                }
            } else if ("permission.asked".equals(type)) {
                String requestId = OpenCodeHttp.text(data, "id");
                if (requestId != null) {
                    String action = OpenCodeHttp.text(data, "action");
                    String message = OpenCodeHttp.text(data, "message");
                    String title = message == null
                            ? (action == null ? "需要确认" : action) : message;
                    Map<String, Object> details = new LinkedHashMap<String, Object>();
                    putIfPresent(details, "resources", toJava(mapper, data.get("resources")));
                    putIfPresent(details, "save", toJava(mapper, data.get("save")));
                    putIfPresent(details, "metadata", toJava(mapper, data.get("metadata")));
                    putIfPresent(details, "source", toJava(mapper, data.get("source")));
                    putIfPresent(details, "message", message);
                    sink.permissionRequested(requestId, title,
                            action == null ? "permission" : action, details);
                }
            } else if ("session.execution.failed".equals(type)) {
                error = parseError(data.get("error"));
                // 2.0.22 起不保证每个 turn 都再发 session.idle，execution.* 才是终止信号
                finishLocked();
            } else if ("session.execution.interrupted".equals(type)) {
                interrupted = true;
                finishLocked();
            } else if ("session.execution.succeeded".equals(type)) {
                error = null;
                interrupted = false;
                finishLocked();
            } else if ("session.step.failed".equals(type)) {
                if (error == null) {
                    error = parseError(data.get("error"));
                }
            } else if ("session.retry.scheduled".equals(type)) {
                error = null;
            } else if ("session.idle".equals(type)) {
                finishLocked();
            } else if ("session.status".equals(type)) {
                JsonNode status = data.get("status");
                if ("idle".equals(OpenCodeHttp.text(status, "type"))) {
                    finishLocked();
                }
            }
        }

        private synchronized void cancelByHost() {
            if (finished) {
                return;
            }
            interrupted = true;
            finishLocked();
        }

        private synchronized void fail(Throwable cause) {
            if (finished) {
                return;
            }
            streamError = cause == null
                    ? new IOException("OpenCode 事件流已断开") : cause;
            finishLocked();
        }

        private synchronized void finishLocked() {
            if (finished) {
                return;
            }
            finished = true;
            done.countDown();
        }

        private void await() throws InterruptedException {
            done.await();
        }

        private synchronized String text() {
            return text.toString();
        }

        private synchronized HarnessException failure() {
            if (streamError != null) {
                return new HarnessException(HarnessException.HARNESS_ERROR,
                        "OpenCode 事件流已断开", null, streamError);
            }
            if (interrupted) {
                return HarnessException.cancelled();
            }
            if (error != null) {
                Map<String, Object> details = new LinkedHashMap<String, Object>();
                if (error.type != null) {
                    details.put("harnessCode", error.type);
                }
                if (error.status > 0) {
                    details.put("status", Integer.valueOf(error.status));
                }
                return new HarnessException(HarnessException.HARNESS_ERROR,
                        "OpenCode: " + error.message, details);
            }
            return null;
        }

        private String textKey(JsonNode data) {
            String messageId = OpenCodeHttp.text(data, "assistantMessageID");
            return (messageId == null ? "unknown" : messageId)
                    + ":" + data.path("ordinal").asInt(0);
        }

        private String toolName(String id) {
            String name = toolNames.get(id);
            return name == null ? "tool" : name;
        }

        private void ensureToolStarted(String id, String name) {
            if (startedTools.add(id)) {
                sink.toolStarted(id, name, null);
            }
        }

        private void putIfPresent(Map<String, Object> target, String key, Object value) {
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static final class OpenCodeError {

        private final String type;
        private final String message;
        private final int status;

        private OpenCodeError(String type, String message, int status) {
            this.type = type;
            this.message = message;
            this.status = status;
        }
    }
}
