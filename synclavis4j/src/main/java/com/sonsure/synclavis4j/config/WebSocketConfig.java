package com.sonsure.synclavis4j.config;

import com.sonsure.synclavis4j.ai.AiEventWebSocketHandler;
import com.sonsure.synclavis4j.websocket.GitEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：仓库事件流、全局事件流与 AI 会话事件流。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GitEventWebSocketHandler gitHandler;
    private final AiEventWebSocketHandler aiHandler;

    public WebSocketConfig(GitEventWebSocketHandler gitHandler,
                           AiEventWebSocketHandler aiHandler) {
        this.gitHandler = gitHandler;
        this.aiHandler = aiHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = {"http://127.0.0.1:*", "http://localhost:*"};
        // 每个仓库一个事件流
        registry.addHandler(gitHandler, "/ws/repos/{repoId}/events")
                .setAllowedOriginPatterns(origins);
        // 全局事件流：clone 等"尚无 repoId"的操作
        registry.addHandler(gitHandler, "/ws/events")
                .setAllowedOriginPatterns(origins);
        // AI 会话事件流（见《详细设计文档》§6.5.3）
        registry.addHandler(aiHandler, "/ws/ai/sessions/{sessionRef}/events")
                .setAllowedOriginPatterns(origins);
    }
}
