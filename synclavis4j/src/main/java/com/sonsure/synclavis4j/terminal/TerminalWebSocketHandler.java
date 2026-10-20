package com.sonsure.synclavis4j.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 终端数据通道：{@code /ws/terminal/sessions/{terminalId}}（见《详细设计文档》§6.6.3）。
 *
 * <p>一个终端最多一个 WebSocket；连接建立时才启动 PTY，断开即终止进程。</p>
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final String MARKER = "/ws/terminal/sessions/";
    private static final String PONG = "{\"type\":\"pong\"}";
    private static final String ATTR_TERMINAL_ID = "terminalId";

    private final TerminalSessionService terminals;
    private final ObjectMapper mapper;

    public TerminalWebSocketHandler(TerminalSessionService terminals, ObjectMapper mapper) {
        this.terminals = terminals;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String terminalId = resolveTerminalId(session);
        if (terminalId == null) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        session.getAttributes().put(ATTR_TERMINAL_ID, terminalId);
        try {
            terminals.attach(terminalId, session.getId(), new WebSocketSink(session));
            log.debug("终端 WS 已连接 terminalId={} ws={}", terminalId, session.getId());
        } catch (GitOperationException e) {
            sendError(session, e.getCode().name(), e.getMessage());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
        } catch (RuntimeException e) {
            log.warn("终端 WS 连接失败 terminalId={}", terminalId, e);
            sendError(session, "TERMINAL_UNAVAILABLE", "终端不可用");
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String terminalId = terminalId(session);
        if (terminalId == null) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        String payload = message.getPayload();
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            log.debug("忽略无法解析的终端 WS 消息: {}", payload);
            return;
        }
        String type = node.path("type").asText("");
        try {
            if ("input".equals(type)) {
                terminals.input(terminalId, node.path("data").asText(""));
                return;
            }
            if ("resize".equals(type)) {
                terminals.resize(terminalId, node.path("cols").asInt(80), node.path("rows").asInt(24));
                return;
            }
            if ("ping".equals(type)) {
                send(session, PONG);
                return;
            }
            if ("close".equals(type)) {
                terminals.delete(terminalId);
                closeQuietly(session, CloseStatus.NORMAL);
            }
        } catch (GitOperationException e) {
            sendError(session, e.getCode().name(), e.getMessage());
            if (e.getCode().httpStatus() == 403 || e.getCode().httpStatus() == 404) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String terminalId = terminalId(session);
        if (terminalId != null) {
            terminals.detach(terminalId, session.getId());
        }
        log.debug("终端 WS 已断开 ws={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("终端 WS 传输异常 ws={}", session.getId(), exception);
        String terminalId = terminalId(session);
        if (terminalId != null) {
            terminals.detach(terminalId, session.getId());
        }
    }

    private String terminalId(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_TERMINAL_ID);
        if (value instanceof String) {
            return (String) value;
        }
        return resolveTerminalId(session);
    }

    private void sendError(WebSocketSession session, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "error");
        payload.put("code", code);
        payload.put("message", message == null ? "" : message);
        try {
            send(session, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("发送终端错误失败 ws={}", session.getId(), e);
        }
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.debug("发送终端消息失败 ws={}", session.getId(), e);
            }
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.close(status);
                }
            }
        } catch (Exception e) {
            log.debug("关闭终端 WS 失败 ws={}", session.getId(), e);
        }
    }

    static String resolveTerminalId(String path) {
        if (path == null || !path.startsWith(MARKER)) {
            return null;
        }
        String terminalId = path.substring(MARKER.length());
        if (terminalId.isEmpty() || terminalId.indexOf('/') >= 0) {
            return null;
        }
        return terminalId;
    }

    private String resolveTerminalId(WebSocketSession session) {
        return session.getUri() == null ? null : resolveTerminalId(session.getUri().getPath());
    }

    /**
     * 把服务层回调转成 WS 文本帧。
     */
    private final class WebSocketSink implements TerminalOutputSink {

        private final WebSocketSession session;

        private WebSocketSink(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void output(String data) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("type", "output");
            payload.put("data", data);
            sendPayload(payload);
        }

        @Override
        public void exit(int code) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("type", "exit");
            payload.put("code", Integer.valueOf(code));
            sendPayload(payload);
            closeQuietly(session, CloseStatus.NORMAL);
        }

        @Override
        public void error(String code, String message) {
            sendError(session, code, message);
        }

        private void sendPayload(Map<String, Object> payload) {
            try {
                send(session, mapper.writeValueAsString(payload));
            } catch (Exception e) {
                log.debug("序列化终端消息失败 ws={}", session.getId(), e);
            }
        }
    }
}
