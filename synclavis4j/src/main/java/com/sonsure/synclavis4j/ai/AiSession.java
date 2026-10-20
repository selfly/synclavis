package com.sonsure.synclavis4j.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宿主侧 AI 会话状态。字段的读写由 {@code AiSessionService.SessionRuntime} 的锁保护。
 */
public class AiSession {

    private String sessionRef;
    private String harnessId;
    private String pluginId;
    private String repoId;
    private String title;
    private String status = "idle";
    private String createdAt;
    private String updatedAt;
    private long lastSeq;
    private String currentTurnId;
    private Map<String, String> lastError;

    public String getSessionRef() {
        return sessionRef;
    }

    public void setSessionRef(String sessionRef) {
        this.sessionRef = sessionRef;
    }

    public String getHarnessId() {
        return harnessId;
    }

    public void setHarnessId(String harnessId) {
        this.harnessId = harnessId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public String getCurrentTurnId() {
        return currentTurnId;
    }

    public void setCurrentTurnId(String currentTurnId) {
        this.currentTurnId = currentTurnId;
    }

    public Map<String, String> getLastError() {
        return lastError;
    }

    public void setLastError(Map<String, String> lastError) {
        this.lastError = lastError == null
                ? null
                : new LinkedHashMap<String, String>(lastError);
    }
}
