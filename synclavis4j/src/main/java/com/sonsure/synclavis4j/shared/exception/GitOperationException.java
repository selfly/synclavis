package com.sonsure.synclavis4j.shared.exception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务异常：携带 {@link ErrorCode} 与可选细节（如冲突文件列表）。
 */
public class GitOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Object details;

    /**
     * §6.1 约定的冲突明细形状：{@code {"files": ["a.txt", "b.txt"]}}。
     *
     * <p>冲突类错误（{@code MERGE_CONFLICT} / {@code CHECKOUT_CONFLICT}）统一用它构造
     * {@code details}，前端按 {@code details.files} 取被覆盖/冲突的文件。</p>
     */
    public static Map<String, Object> conflictDetails(List<String> files) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("files", files == null ? new ArrayList<String>() : new ArrayList<String>(files));
        return details;
    }

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
