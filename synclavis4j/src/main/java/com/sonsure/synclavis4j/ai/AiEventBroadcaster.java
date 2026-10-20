package com.sonsure.synclavis4j.ai;

/**
 * AI 事件出口。生产实现走 WebSocket；测试可用 no-op 实现。
 */
public interface AiEventBroadcaster {

    void broadcast(AiEvent event);
}
