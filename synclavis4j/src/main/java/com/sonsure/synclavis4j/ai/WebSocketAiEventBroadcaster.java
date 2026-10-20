package com.sonsure.synclavis4j.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonsure.synclavis4j.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把归一化 AI 事件广播到 {@code /ws/ai/sessions/{ref}/events}。
 */
@Component
public class WebSocketAiEventBroadcaster implements AiEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAiEventBroadcaster.class);

    private final WebSocketSessionManager sessions;
    private final ObjectMapper mapper;

    public WebSocketAiEventBroadcaster(WebSocketSessionManager sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    public void broadcast(AiEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            sessions.broadcastChannel(AiEventChannels.of(event.getSessionRef()), json);
        } catch (JsonProcessingException e) {
            log.warn("AI 事件序列化失败 session={} seq={}",
                    event.getSessionRef(), event.getSeq(), e);
        }
    }
}
