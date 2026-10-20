package com.sonsure.synclavis4j.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /api/ai/sessions/{ref}/events} 的响应数据。
 *
 * <p>{@code truncated=true} 表示请求的 {@code afterSeq} 之前已有事件被挤出内存缓冲，
 * 客户端需要重新拉会话状态或从更早位置重新同步。</p>
 */
public class AiEventsDto {

    private String sessionRef;
    private long lastSeq;
    private boolean truncated;
    private List<AiEvent> events = new ArrayList<AiEvent>();

    public AiEventsDto() {
    }

    public AiEventsDto(String sessionRef, long lastSeq, boolean truncated, List<AiEvent> events) {
        this.sessionRef = sessionRef;
        this.lastSeq = lastSeq;
        this.truncated = truncated;
        this.events = events == null ? new ArrayList<AiEvent>() : events;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public void setSessionRef(String sessionRef) {
        this.sessionRef = sessionRef;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<AiEvent> getEvents() {
        return events;
    }

    public void setEvents(List<AiEvent> events) {
        this.events = events == null ? new ArrayList<AiEvent>() : events;
    }
}
