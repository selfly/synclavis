package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * provider 向宿主发事件的唯一出口。
 *
 * <p>provider 不得直接操作 WebSocket；核心负责补 {@code sessionRef/turnId/seq/at}、
 * 缓冲与广播。常用事件提供了默认方法，避免各适配器重复拼 payload。</p>
 */
public interface HarnessEventSink {

    void emit(String type, Map<String, Object> payload);

    /** 当前 turn 是否已被取消/会话是否已关闭。provider 应在长循环中主动检查。 */
    boolean isCancelled();

    default void messageDelta(String text) {
        emit(AiEventTypes.MESSAGE_DELTA, payload("text", text));
    }

    default void messageCompleted(String text) {
        emit(AiEventTypes.MESSAGE_COMPLETED, payload("text", text));
    }

    default void toolStarted(String toolCallId, String name, Object input) {
        Map<String, Object> payload = payload("toolCallId", toolCallId);
        payload.put("name", name);
        payload.put("input", input);
        emit(AiEventTypes.TOOL_STARTED, payload);
    }

    default void toolCompleted(String toolCallId, String name, boolean ok,
                               Object output, String error) {
        Map<String, Object> payload = payload("toolCallId", toolCallId);
        payload.put("name", name);
        payload.put("ok", Boolean.valueOf(ok));
        payload.put("output", output);
        payload.put("error", error);
        emit(AiEventTypes.TOOL_COMPLETED, payload);
    }

    default void permissionRequested(String requestId, String title, String kind,
                                     Map<String, Object> details) {
        Map<String, Object> payload = payload("requestId", requestId);
        payload.put("title", title);
        payload.put("kind", kind);
        payload.put("details", details == null ? new LinkedHashMap<String, Object>() : details);
        emit(AiEventTypes.PERMISSION_REQUESTED, payload);
    }

    default void diffUpdated(List<Map<String, Object>> files) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("files", files);
        emit(AiEventTypes.DIFF_UPDATED, payload);
    }

    static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(key, value);
        return payload;
    }
}
