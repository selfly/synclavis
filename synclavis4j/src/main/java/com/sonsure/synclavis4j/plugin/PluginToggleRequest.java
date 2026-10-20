package com.sonsure.synclavis4j.plugin;

/**
 * 插件启停 / 开发者模式请求。
 */
public class PluginToggleRequest {

    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
