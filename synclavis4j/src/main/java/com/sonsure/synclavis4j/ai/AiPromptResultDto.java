package com.sonsure.synclavis4j.ai;

/**
 * prompt 提交结果；{@code turnId} 同时作为响应信封的 {@code operationId}。
 */
public class AiPromptResultDto {

    private final String sessionRef;
    private final String turnId;

    public AiPromptResultDto(String sessionRef, String turnId) {
        this.sessionRef = sessionRef;
        this.turnId = turnId;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public String getTurnId() {
        return turnId;
    }
}
