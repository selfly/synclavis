package com.sonsure.synclavis4j.shared.util;

/**
 * 传输类异常的分类工具。
 *
 * <p>异步操作（fetch/pull/push/clone）的异常不会经过 {@code GlobalExceptionHandler}
 * （那里只覆盖同步 HTTP 调用），必须在写执行器里自行分类，否则网络/认证失败会
 * 被误报为 500 INTERNAL。</p>
 */
public final class TransportErrors {

    private static final int MAX_DEPTH = 8;

    private TransportErrors() {
    }

    /** 是否为传输层异常（api.errors 与 errors 两套都要认）。 */
    public static boolean isTransport(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            if (current instanceof org.eclipse.jgit.api.errors.TransportException
                    || current instanceof org.eclipse.jgit.errors.TransportException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /** 是否为用户取消（JGit 通过 CanceledException 中止）。 */
    public static boolean isCancellation(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            if (current instanceof org.eclipse.jgit.api.errors.CanceledException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /** 从异常链的消息中判断是否为认证/授权失败。 */
    public static boolean isAuthFailure(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("not authorized")
                        || lower.contains("authentication")
                        || lower.contains("auth fail")
                        || lower.contains("permission denied")
                        || lower.contains("invalid credentials")
                        || lower.contains("401")) {
                    return true;
                }
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }
}
