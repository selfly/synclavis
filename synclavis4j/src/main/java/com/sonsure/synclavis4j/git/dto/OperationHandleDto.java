package com.sonsure.synclavis4j.git.dto;

/**
 * 异步操作句柄：客户端凭 {@code operationId} 订阅 WebSocket 事件。
 */
public class OperationHandleDto {

    private String operationId;

    public OperationHandleDto() {
    }

    public OperationHandleDto(String operationId) {
        this.operationId = operationId;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }
}
