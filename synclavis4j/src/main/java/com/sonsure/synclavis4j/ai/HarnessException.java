package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * harness 层异常：携带稳定错误码，由核心归一化为 AI 事件或 HTTP 错误。
 *
 * <p>错误码使用字符串，避免 harness 适配器依赖 Web 层枚举。常用值见
 * {@link #CANCELLED} / {@link #UNSUPPORTED} / {@link #HARNESS_ERROR}。</p>
 */
public class HarnessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CANCELLED = "CANCELLED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String HARNESS_ERROR = "HARNESS_ERROR";

    private final String code;
    private final transient Map<String, Object> details;

    public HarnessException(String code, String message) {
        this(code, message, null, null);
    }

    public HarnessException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public HarnessException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public HarnessException(String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.isEmpty() ? HARNESS_ERROR : code;
        this.details = details == null ? null : new LinkedHashMap<String, Object>(details);
    }

    public static HarnessException cancelled() {
        return new HarnessException(CANCELLED, "已取消");
    }

    public static HarnessException unsupported(String message) {
        return new HarnessException(UNSUPPORTED, message);
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
