package com.sonsure.synclavis4j.ai;

/**
 * {@code POST /api/ai/sessions} 请求。
 */
public class CreateAiSessionRequest {

    private String harnessId;
    private String pluginId;
    private String repoId;
    private String title;

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
}
