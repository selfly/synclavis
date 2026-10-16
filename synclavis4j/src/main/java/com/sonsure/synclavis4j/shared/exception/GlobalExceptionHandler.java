package com.sonsure.synclavis4j.shared.exception;

import com.sonsure.synclavis4j.shared.dto.ApiResponse;
import com.sonsure.synclavis4j.shared.dto.ErrorBody;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把 JGit 异常与业务异常统一映射为响应信封（见《详细设计文档》§5.8）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 合并冲突（JGit 的合并冲突即 CheckoutConflictException，注意不是 MergeConflictException）。 */
    @ExceptionHandler(CheckoutConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleCheckoutConflict(CheckoutConflictException e) {
        return build(ErrorCode.MERGE_CONFLICT, e.getMessage(), null);
    }

    /** Stash Apply 冲突。 */
    @ExceptionHandler(StashApplyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleStashApplyFailure(StashApplyFailureException e) {
        return build(ErrorCode.STASH_CONFLICT, e.getMessage(), null);
    }

    /** 路径不是 Git 仓库。 */
    @ExceptionHandler(RepositoryNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleRepositoryNotFound(RepositoryNotFoundException e) {
        return build(ErrorCode.NOT_A_REPO, e.getMessage(), null);
    }

    /** 远程传输失败（高层 API 与低层异常都捕获）。 */
    @ExceptionHandler({org.eclipse.jgit.api.errors.TransportException.class,
            org.eclipse.jgit.errors.TransportException.class})
    public ResponseEntity<ApiResponse<Void>> handleTransport(Exception e) {
        return build(ErrorCode.REMOTE_ERROR, e.getMessage(), null);
    }

    /** 业务异常：按内部 code 分发状态码。 */
    @ExceptionHandler(GitOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleGitOperation(GitOperationException e) {
        return build(e.getCode(), e.getMessage(), e.getDetails());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return build(ErrorCode.INTERNAL, e.getMessage(), null);
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, Object details) {
        ErrorBody body = new ErrorBody(code.name(), message, details);
        HttpStatus status = HttpStatus.valueOf(code.httpStatus());
        return ResponseEntity.status(status).body(ApiResponse.<Void>fail(body, null));
    }
}
