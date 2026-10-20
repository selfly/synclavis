package com.sonsure.synclavis4j.ai;

/**
 * AI harness 后端 SPI（见《详细设计文档》§6.5）。
 *
 * <p>实现必须是线程安全的：同一 provider 可能同时服务多个会话。{@link #prompt} 由核心在
 * AI 线程池中调用，允许阻塞到本轮结束；中间事件通过 {@link HarnessEventSink} 推送。</p>
 */
public interface HarnessProvider {

    /** 描述 harness 与能力；应廉价且稳定，可在列表接口中被频繁调用。 */
    HarnessDescriptor descriptor();

    HarnessSessionHandle createSession(HarnessSessionRequest request) throws HarnessException;

    void prompt(HarnessSessionHandle handle, HarnessPrompt prompt, HarnessEventSink sink)
            throws HarnessException;

    /** 请求中止当前 turn；provider 应尽快让 prompt 返回或抛出取消异常。 */
    default void cancel(HarnessSessionHandle handle) throws HarnessException {
    }

    /** 回传用户对权限请求的决定；只有 {@code capabilities.permissions=true} 时才会被调用。 */
    default void respondPermission(HarnessSessionHandle handle, String requestId, String decision)
            throws HarnessException {
        throw HarnessException.unsupported("harness 不支持权限确认");
    }

    /** 会话关闭/后端退出时释放原生资源。 */
    default void closeSession(HarnessSessionHandle handle) {
    }
}
