package com.sonsure.synclavis4j.state;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地状态聚合根：会话 + 全局设置 + 每个仓库的设置。
 *
 * <p>结构即《详细设计文档》附录 B 的状态文件格式，属于跨后端共用契约。</p>
 */
public class AppState {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private SessionState session = new SessionState();
    private GlobalSettings globalSettings = new GlobalSettings();
    private Map<String, RepoSettings> repos = new LinkedHashMap<String, RepoSettings>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public SessionState getSession() {
        return session;
    }

    public void setSession(SessionState session) {
        this.session = session == null ? new SessionState() : session;
    }

    public GlobalSettings getGlobalSettings() {
        return globalSettings;
    }

    public void setGlobalSettings(GlobalSettings globalSettings) {
        this.globalSettings = globalSettings == null ? new GlobalSettings() : globalSettings;
    }

    public Map<String, RepoSettings> getRepos() {
        return repos;
    }

    public void setRepos(Map<String, RepoSettings> repos) {
        this.repos = repos == null ? new LinkedHashMap<String, RepoSettings>() : repos;
    }

    /** 取（必要时创建）某仓库的设置。 */
    public RepoSettings settingsOf(String repoId) {
        RepoSettings settings = repos.get(repoId);
        if (settings == null) {
            settings = new RepoSettings();
            repos.put(repoId, settings);
        }
        return settings;
    }
}
