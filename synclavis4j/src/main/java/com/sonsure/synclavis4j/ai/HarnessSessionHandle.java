package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * harness 原生会话句柄。
 *
 * <p>{@code nativeRef} 对宿主不透明，只用于把 prompt/cancel/权限操作交还给同一个
 * provider；不会出现在任何 API 或事件中。{@code attributes} 由 provider 自用，
 * 宿主只负责保存，不读取、不序列化。</p>
 */
public class HarnessSessionHandle {

    private final String nativeRef;
    private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    public HarnessSessionHandle(String nativeRef) {
        this.nativeRef = nativeRef;
    }

    public String getNativeRef() {
        return nativeRef;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }
}
