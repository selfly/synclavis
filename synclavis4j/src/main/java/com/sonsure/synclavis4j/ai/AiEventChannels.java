package com.sonsure.synclavis4j.ai;

/**
 * AI WebSocket 通道命名：与仓库事件共用 {@code WebSocketSessionManager}，但使用独立前缀，
 * 避免和 repoId 冲突。
 */
public final class AiEventChannels {

    private static final String PREFIX = "ai:";

    private AiEventChannels() {
    }

    public static String of(String sessionRef) {
        return PREFIX + sessionRef;
    }
}
