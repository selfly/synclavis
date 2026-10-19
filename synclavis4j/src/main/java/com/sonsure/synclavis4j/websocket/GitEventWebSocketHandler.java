package com.sonsure.synclavis4j.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.operation.OperationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 仓库事件 WebSocket 处理器。
 *
 * <p>端点：{@code /ws/repos/{repoId}/events}。</p>
 *
 * <p>客户端可发送：</p>
 * <ul>
 *   <li>{@code {"type":"ping"}} —— 保活，服务端回 {@code {"type":"pong"}}</li>
 *   <li>{@code {"type":"cancel","operationId":"op-xxxx"}} —— 请求取消进行中的操作；
 *       实际中止发生在 JGit 调用 {@code ProgressMonitor.isCancelled()} 的检查点，
 *       结果通过常规的 error(CANCELLED) + done(false) 事件回报。</li>
 * </ul>
 */
@Component
public class GitEventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GitEventWebSocketHandler.class);

    private static final String PONG = "{\"type\":\"pong\"}";

    private final WebSocketSessionManager sessions;
    private final OperationRegistry operations;
    private final ObjectMapper mapper;

    public GitEventWebSocketHandler(WebSocketSessionManager sessions,
                                    OperationRegistry operations,
                                    ObjectMapper mapper) {
        this.sessions = sessions;
        this.operations = operations;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String repoId = resolveRepoId(session);
        if (repoId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        sessions.register(repoId, session);
        log.debug("WS 已连接 repoId={} sessionId={}", repoId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            log.debug("忽略无法解析的 WS 消息: {}", payload);
            return;
        }
        String type = node.path("type").asText("");

        if ("ping".equals(type)) {
            send(session, PONG);
            return;
        }

        if ("cancel".equals(type)) {
            String operationId = node.path("operationId").asText(null);
            boolean accepted = operationId != null && operations.cancel(operationId);
            log.debug("收到取消请求 operationId={} accepted={}", operationId, accepted);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String repoId = resolveRepoId(session);
        if (repoId != null) {
            sessions.unregister(repoId, session);
        }
        log.debug("WS 已断开 sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输异常 sessionId={}", session.getId(), exception);
    }

    private void send(WebSocketSession session, String json) throws Exception {
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    /**
     * 解析订阅通道：
     * <ul>
     *   <li>{@code /ws/repos/{repoId}/events} → 该 repoId</li>
     *   <li>{@code /ws/events} → 全局通道（clone 等尚无 repoId 的操作）</li>
     * </ul>
     */
    private String resolveRepoId(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        if (path.endsWith("/ws/events") || "/ws/events".equals(path)) {
            return WebSocketSessionManager.GLOBAL_REPO_ID;
        }
        String marker = "/ws/repos/";
        int start = path.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = path.indexOf('/', start);
        if (end <= start) {
            return null;
        }
        return path.substring(start, end);
    }
}
