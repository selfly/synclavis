package com.sonsure.synclavis4j.ai;

/**
 * {@code POST /api/ai/sessions/{ref}/cancel} 请求；{@code turnId} 省略时取消当前 turn。
 */
public class AiCancelRequest {

    private String turnId;

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }
}
