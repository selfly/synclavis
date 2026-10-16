package com.sonsure.synclavis4j.shared.exception;

/**
 * 统一错误码与 HTTP 状态映射（见《详细设计文档》§5.5 / §6.2）。
 *
 * <p>新增错误码时必须同步：本枚举、GlobalExceptionHandler、文档错误码表。</p>
 */
public enum ErrorCode {

    // 409 —— 冲突与状态冲突
    MERGE_CONFLICT(409),
    REBASE_CONFLICT(409),
    STASH_CONFLICT(409),
    REBASE_IN_PROGRESS(409),
    REPO_BUSY(409),
    CANCELLED(409),
    PULL_NOT_FAST_FORWARD(409),

    // 400 —— 参数 / 前置条件不满足
    NO_UPSTREAM(400),
    NOT_A_REPO(400),
    INVALID_PATH(400),
    NOTHING_TO_COMMIT(400),
    REBASE_FAILED(400),
    WORKTREE_NOT_CLEAN(400),
    PUSH_REJECTED_NON_FAST_FORWARD(400),
    PUSH_REJECTED_STALE(400),

    // 401 / 403 / 404
    AUTH_FAILED(401),
    SSH_KEY_PASSPHRASE_UNSUPPORTED(401),
    PERMISSION_DENIED(403),
    NOT_FOUND(404),

    // 502 / 500
    REMOTE_ERROR(502),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
