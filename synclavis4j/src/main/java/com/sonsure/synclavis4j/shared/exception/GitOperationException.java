package com.sonsure.synclavis4j.shared.exception;

/**
 * 业务异常：携带 {@link ErrorCode} 与可选细节（如冲突文件列表）。
 */
public class GitOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Object details;

    public GitOperationException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public GitOperationException(ErrorCode code, String message, Object details) {
        this(code, message, details, null);
    }

    public GitOperationException(ErrorCode code, String message, Object details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
