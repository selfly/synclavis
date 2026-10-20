package com.sonsure.synclavis4j.ai.opencode;

/**
 * OpenCode 调用失败；{@code status=0} 表示连接层失败。
 */
class OpenCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    OpenCodeException(int status, String message) {
        super(message);
        this.status = status;
    }

    OpenCodeException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    int getStatus() {
        return status;
    }
}
