package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 归一化后的 AI 事件（见《详细设计文档》§6.5.2）。
 *
 * <p>{@code sessionRef/turnId/seq/at} 由核心盖章，provider 只能决定 {@code type} 与
 * {@code payload}，从而保证所有 harness 的事件形状一致。</p>
 */
public class AiEvent {

    private final String type;
    private final String sessionRef;
    private final String turnId;
    private final long seq;
    private final String at;
    private final Map<String, Object> payload;

    public AiEvent(String type, String sessionRef, String turnId, long seq, String at,
                   Map<String, Object> payload) {
        this.type = type;
        this.sessionRef = sessionRef;
        this.turnId = turnId;
        this.seq = seq;
        this.at = at;
        this.payload = payload == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(payload);
    }

    public String getType() {
        return type;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public String getTurnId() {
        return turnId;
    }

    public long getSeq() {
        return seq;
    }

    public String getAt() {
        return at;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
