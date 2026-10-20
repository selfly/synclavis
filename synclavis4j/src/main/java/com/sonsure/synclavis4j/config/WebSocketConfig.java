package com.sonsure.synclavis4j.config;

import com.sonsure.synclavis4j.ai.AiEventWebSocketHandler;
import com.sonsure.synclavis4j.terminal.TerminalWebSocketHandler;
import com.sonsure.synclavis4j.websocket.GitEventWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 注册：仓库事件流、全局事件流、AI 会话事件流与终端数据通道。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GitEventWebSocketHandler gitHandler;
    private final AiEventWebSocketHandler aiHandler;
    private final TerminalWebSocketHandler terminalHandler;

    public WebSocketConfig(GitEventWebSocketHandler gitHandler,
                           AiEventWebSocketHandler aiHandler,
                           TerminalWebSocketHandler terminalHandler) {
        this.gitHandler = gitHandler;
        this.aiHandler = aiHandler;
        this.terminalHandler = terminalHandler;
    }

    /**
     * 终端输入单帧上限 64KB；前端按 8K 字符分片，避免 Tomcat 默认 8KB 直接断连。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
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
        // 终端数据通道（见《详细设计文档》§6.6.3）
        registry.addHandler(terminalHandler, "/ws/terminal/sessions/{terminalId}")
                .setAllowedOriginPatterns(origins);
    }
}
