package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code AiSession} 的 API 视图；不包含 harness 原生 session id。
 */
public class AiSessionDto {

    private String sessionRef;
    private String harnessId;
    private String pluginId;
    private String repoId;
    private String title;
    private String status;
    private String createdAt;
    private String updatedAt;
    private long lastSeq;
    private String currentTurnId;
    private Map<String, String> lastError;

    public static AiSessionDto from(AiSession session) {
        AiSessionDto dto = new AiSessionDto();
        dto.sessionRef = session.getSessionRef();
        dto.harnessId = session.getHarnessId();
        dto.pluginId = session.getPluginId();
        dto.repoId = session.getRepoId();
        dto.title = session.getTitle();
        dto.status = session.getStatus();
        dto.createdAt = session.getCreatedAt();
        dto.updatedAt = session.getUpdatedAt();
        dto.lastSeq = session.getLastSeq();
        dto.currentTurnId = session.getCurrentTurnId();
        Map<String, String> error = session.getLastError();
        dto.lastError = error == null ? null : new LinkedHashMap<String, String>(error);
        return dto;
    }

    public String getSessionRef() {
        return sessionRef;
    }

    public String getHarnessId() {
        return harnessId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getRepoId() {
        return repoId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public String getCurrentTurnId() {
        return currentTurnId;
    }

    public Map<String, String> getLastError() {
        return lastError;
    }
}
