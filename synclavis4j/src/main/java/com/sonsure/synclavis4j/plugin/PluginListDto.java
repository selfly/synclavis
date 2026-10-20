package com.sonsure.synclavis4j.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/plugins} 的响应数据。
 */
public class PluginListDto {

    private boolean developerMode;
    private String pluginsDir;
    private Map<String, String> activeViews = new LinkedHashMap<String, String>();
    private List<PluginInfo> plugins = new ArrayList<PluginInfo>();

    public boolean isDeveloperMode() {
        return developerMode;
    }

    public void setDeveloperMode(boolean developerMode) {
        this.developerMode = developerMode;
    }

    public String getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(String pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    public Map<String, String> getActiveViews() {
        return activeViews;
    }

    public void setActiveViews(Map<String, String> activeViews) {
        this.activeViews = activeViews == null
                ? new LinkedHashMap<String, String>() : activeViews;
    }

    public List<PluginInfo> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<PluginInfo> plugins) {
        this.plugins = plugins == null ? new ArrayList<PluginInfo>() : plugins;
    }
}
