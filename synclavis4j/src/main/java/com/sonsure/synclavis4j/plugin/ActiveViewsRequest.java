package com.sonsure.synclavis4j.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存每个插槽当前选中的插件视图。
 */
public class ActiveViewsRequest {

    private Map<String, String> activeViews = new LinkedHashMap<String, String>();

    public Map<String, String> getActiveViews() {
        return activeViews;
    }

    public void setActiveViews(Map<String, String> activeViews) {
        this.activeViews = activeViews == null
                ? new LinkedHashMap<String, String>() : activeViews;
    }
}
