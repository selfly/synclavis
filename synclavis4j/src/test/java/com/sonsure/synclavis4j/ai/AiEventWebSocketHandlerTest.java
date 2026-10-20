package com.sonsure.synclavis4j.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI 事件流路径解析：必须只接受 {@code /ws/ai/sessions/{ref}/events}。
 */
class AiEventWebSocketHandlerTest {

    @Test
    void resolvesSessionRefFromEventPath() {
        assertEquals("ai_abc123",
                AiEventWebSocketHandler.resolveSessionRef("/ws/ai/sessions/ai_abc123/events"));
        assertNull(AiEventWebSocketHandler.resolveSessionRef("/ws/ai/sessions/ai_abc123"));
        assertNull(AiEventWebSocketHandler.resolveSessionRef("/ws/ai/sessions//events"));
        assertNull(AiEventWebSocketHandler.resolveSessionRef("/ws/ai/sessions/ai_abc123/events/extra"));
        assertNull(AiEventWebSocketHandler.resolveSessionRef("/ws/repos/abc/events"));
        assertNull(AiEventWebSocketHandler.resolveSessionRef(null));
    }
}
