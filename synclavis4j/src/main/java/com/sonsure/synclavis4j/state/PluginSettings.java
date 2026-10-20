package com.sonsure.synclavis4j.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件持久化设置（见《详细设计文档》附录 B）。
 *
 * <p>只保存宿主需要知道的状态：启用列表、开发者模式、插件自定义设置、
 * 每个插槽当前选中的视图、每个插件当前选择的 AI harness。插件自己的业务数据
 * 应放在插件数据目录，不塞进状态文件。</p>
 */
public class PluginSettings {

    private List<String> enabled = new ArrayList<String>();
    private boolean developerMode = false;
    private Map<String, Object> settings = new LinkedHashMap<String, Object>();
    private Map<String, String> activeViews = new LinkedHashMap<String, String>();
    private Map<String, String> activeHarness = new LinkedHashMap<String, String>();

    public List<String> getEnabled() {
        return enabled;
    }

    public void setEnabled(List<String> enabled) {
        this.enabled = enabled == null ? new ArrayList<String>() : enabled;
    }

    public boolean isDeveloperMode() {
        return developerMode;
    }

    public void setDeveloperMode(boolean developerMode) {
        this.developerMode = developerMode;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings == null ? new LinkedHashMap<String, Object>() : settings;
    }

    public Map<String, String> getActiveViews() {
        return activeViews;
    }

    public void setActiveViews(Map<String, String> activeViews) {
        this.activeViews = activeViews == null ? new LinkedHashMap<String, String>() : activeViews;
    }

    public Map<String, String> getActiveHarness() {
        return activeHarness;
    }

    public void setActiveHarness(Map<String, String> activeHarness) {
        this.activeHarness = activeHarness == null ? new LinkedHashMap<String, String>() : activeHarness;
    }
}
