package com.sonsure.synclavis4j.operation;

/**
 * 进行中操作的上下文。
 */
public class OperationContext {

    public enum Status {
        RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    private final String operationId;
    private final String repoId;
    private final String kind;
    private final long startedAt;

    private volatile Status status = Status.RUNNING;
    private volatile String errorCode;
    private volatile String errorMessage;
    private volatile boolean cancelRequested;

    public OperationContext(String operationId, String repoId, String kind) {
        this.operationId = operationId;
        this.repoId = repoId;
        this.kind = kind;
        this.startedAt = System.currentTimeMillis();
    }

    public String getOperationId() {
        return operationId;
    }

    public String getRepoId() {
        return repoId;
    }

    public String getKind() {
        return kind;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }
}
