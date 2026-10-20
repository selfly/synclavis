package com.sonsure.synclavis4j.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * AI 会话事件流：{@code /ws/ai/sessions/{sessionRef}/events}。
 *
 * <p>客户端可发 {@code ping} 与 {@code cancel}（{@code turnId} 或 {@code operationId}）。
 * 事件本身只做实时推送，重连补偿走 HTTP 事件接口。</p>
 */
@Component
public class AiEventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AiEventWebSocketHandler.class);

    private static final String PONG = "{\"type\":\"pong\"}";
    private static final String MARKER = "/ws/ai/sessions/";

    private final AiSessionService aiSessions;
    private final WebSocketSessionManager sessions;
    private final ObjectMapper mapper;

    public AiEventWebSocketHandler(AiSessionService aiSessions,
                                   WebSocketSessionManager sessions,
                                   ObjectMapper mapper) {
        this.aiSessions = aiSessions;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionRef = resolveSessionRef(session);
        log.debug("AI WS 连接 path={} sessionRef={}",
                session.getUri() == null ? null : session.getUri().getPath(), sessionRef);
        if (sessionRef == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        try {
            aiSessions.getSession(sessionRef);
        } catch (RuntimeException e) {
            log.debug("AI WS 会话不存在 sessionRef={}", sessionRef, e);
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        sessions.registerChannel(AiEventChannels.of(sessionRef), session);
        log.debug("AI WS 已连接 sessionRef={} ws={}", sessionRef, session.getId());
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
            log.debug("忽略无法解析的 AI WS 消息: {}", payload);
            return;
        }
        String type = node.path("type").asText("");
        if ("ping".equals(type)) {
            send(session, PONG);
            return;
        }
        if ("cancel".equals(type)) {
            String sessionRef = resolveSessionRef(session);
            if (sessionRef == null) {
                return;
            }
            String turnId = node.path("turnId").asText(null);
            if (turnId == null || turnId.isEmpty()) {
                turnId = node.path("operationId").asText(null);
            }
            boolean accepted = aiSessions.cancel(sessionRef, turnId);
            log.debug("收到 AI 取消请求 sessionRef={} turnId={} accepted={}",
                    sessionRef, turnId, accepted);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionRef = resolveSessionRef(session);
        if (sessionRef != null) {
            sessions.unregisterChannel(AiEventChannels.of(sessionRef), session);
        }
        log.debug("AI WS 已断开 sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("AI WS 传输异常 sessionId={}", session.getId(), exception);
    }

    private void send(WebSocketSession session, String json) throws Exception {
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    private String resolveSessionRef(WebSocketSession session) {
        return session.getUri() == null ? null : resolveSessionRef(session.getUri().getPath());
    }

    static String resolveSessionRef(String path) {
        if (path == null) {
            return null;
        }
        int start = path.indexOf(MARKER);
        if (start < 0) {
            return null;
        }
        start += MARKER.length();
        int end = path.indexOf('/', start);
        if (end <= start) {
            return null;
        }
        String sessionRef = path.substring(start, end);
        if (sessionRef.isEmpty() || sessionRef.indexOf('/') >= 0) {
            return null;
        }
        // 只接受 /ws/ai/sessions/{ref}/events
        if (path.length() != end + "/events".length() || !path.endsWith("/events")) {
            return null;
        }
        return sessionRef;
    }
}
