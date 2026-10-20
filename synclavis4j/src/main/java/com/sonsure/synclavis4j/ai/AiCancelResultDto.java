package com.sonsure.synclavis4j.ai;

/**
 * 取消结果；没有进行中的 turn 或 turnId 不匹配时为 {@code false}。
 */
public class AiCancelResultDto {

    private final boolean cancelled;

    public AiCancelResultDto(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
