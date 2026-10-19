package com.sonsure.synclavis4j.config;

import com.sonsure.synclavis4j.websocket.GitEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：每个仓库一个事件端点 {@code /ws/repos/{repoId}/events}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GitEventWebSocketHandler handler;

    public WebSocketConfig(GitEventWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 每个仓库一个事件流
        registry.addHandler(handler, "/ws/repos/{repoId}/events")
                .setAllowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*");
        // 全局事件流：clone 等"尚无 repoId"的操作
        registry.addHandler(handler, "/ws/events")
                .setAllowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*");
    }
}
