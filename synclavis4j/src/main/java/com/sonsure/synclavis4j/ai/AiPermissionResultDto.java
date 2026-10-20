package com.sonsure.synclavis4j.ai;

/**
 * 权限确认结果。
 */
public class AiPermissionResultDto {

    private final String requestId;
    private final String decision;

    public AiPermissionResultDto(String requestId, String decision) {
        this.requestId = requestId;
        this.decision = decision;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDecision() {
        return decision;
    }
}
