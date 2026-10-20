package com.sonsure.synclavis4j.ai;

/**
 * {@code POST /api/ai/sessions/{ref}/permissions/{requestId}} 请求。
 */
public class AiPermissionRequest {

    private String decision;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }
}
