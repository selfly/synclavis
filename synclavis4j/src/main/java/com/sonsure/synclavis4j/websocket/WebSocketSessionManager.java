package com.sonsure.synclavis4j.websocket;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 按 repoId 维护 WebSocket 会话，并提供广播能力。
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    /**
     * 全局事件通道的伪 repoId。用于「尚未打开仓库」的操作（如 clone）推送进度：
     * 此时还没有 repoId 可供订阅。前端订阅 {@code /ws/events}。
     */
    public static final String GLOBAL_REPO_ID = "__global__";

    private final Map<String, Set<WebSocketSession>> sessionsByRepo =
            new ConcurrentHashMap<String, Set<WebSocketSession>>();

    public void register(String repoId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByRepo.get(repoId);
        if (sessions == null) {
            Set<WebSocketSession> created =
                    Collections.newSetFromMap(new ConcurrentHashMap<WebSocketSession, Boolean>());
            sessions = sessionsByRepo.putIfAbsent(repoId, created);
            if (sessions == null) {
                sessions = created;
            }
        }
        sessions.add(session);
    }

    public void unregister(String repoId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByRepo.get(repoId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByRepo.remove(repoId);
        }
    }

    /**
     * 向订阅了该仓库的会话广播一条 JSON 消息。
     */
    public void broadcast(String repoId, String json) {
        Set<WebSocketSession> sessions = sessionsByRepo.get(repoId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            sendSafe(session, message);
        }
    }

    public int sessionCount(String repoId) {
        Set<WebSocketSession> sessions = sessionsByRepo.get(repoId);
        return sessions == null ? 0 : sessions.size();
    }

    /**
     * 通用通道别名：AI 等非仓库事件流复用同一套会话管理，但 channel 不是 repoId。
     */
    public void registerChannel(String channel, WebSocketSession session) {
        register(channel, session);
    }

    public void unregisterChannel(String channel, WebSocketSession session) {
        unregister(channel, session);
    }

    public void broadcastChannel(String channel, String json) {
        broadcast(channel, json);
    }

    private void sendSafe(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            return;
        }
        // WebSocketSession 非线程安全，发送需串行
        synchronized (session) {
            try {
                session.sendMessage(message);
            } catch (IOException e) {
                log.debug("WS 发送失败 session={}", session.getId(), e);
            }
        }
    }
}
