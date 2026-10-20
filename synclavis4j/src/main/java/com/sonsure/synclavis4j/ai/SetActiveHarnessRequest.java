package com.sonsure.synclavis4j.ai;

/**
 * {@code PUT /api/ai/harnesses/active} 请求：按插件记录当前选择的 harness。
 */
public class SetActiveHarnessRequest {

    private String pluginId;
    private String harnessId;

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getHarnessId() {
        return harnessId;
    }

    public void setHarnessId(String harnessId) {
        this.harnessId = harnessId;
    }
}
