package com.sonsure.synclavis4j.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.git.dto.RepoStatusDto;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.websocket.WebSocketSessionManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 仓库事件出口：统一把 progress / log / status / error / done 推到 WebSocket。
 *
 * <p>消息格式见《详细设计文档》§4.1 / §8.4，属于跨后端共用契约。</p>
 */
@Component
public class RepoEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RepoEventPublisher.class);

    private final WebSocketSessionManager sessions;
    private final ObjectMapper mapper;

    public RepoEventPublisher(WebSocketSessionManager sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public void publishProgress(String repoId, String operationId, String title, int completed, int total) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("completed", completed);
        payload.put("total", total);
        send(repoId, "progress", operationId, payload);
    }

    public void publishLog(String repoId, String operationId, String level, String text) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("level", level);
        payload.put("text", text);
        send(repoId, "log", operationId, payload);
    }

    public void publishStatus(String repoId, RepoStatusDto status) {
        send(repoId, "status", null, status);
    }

    public void publishError(String repoId, String operationId, ErrorCode code, String message, Object details) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("code", code.name());
        payload.put("message", message);
        payload.put("details", details);
        send(repoId, "error", operationId, payload);
    }

    public void publishDone(String repoId, String operationId, boolean ok) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ok", ok);
        send(repoId, "done", operationId, payload);
    }

    private void send(String repoId, String type, String operationId, Object payload) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("type", type);
        message.put("repoId", repoId);
        if (operationId != null) {
            message.put("operationId", operationId);
        }
        message.put("payload", payload);
        try {
            sessions.broadcast(repoId, mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.warn("事件序列化失败 type={} repoId={}", type, repoId, e);
        }
    }
}
