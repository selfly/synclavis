package com.sonsure.synclavis4j.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局设置：主题、快捷键、最近仓库。后端持久化（前端 localStorage 仅作瞬时缓存）。
 */
public class GlobalSettings {

    public static final String DEFAULT_THEME = "dark";

    private String theme = DEFAULT_THEME;
    private Map<String, String> shortcuts = defaultShortcuts();
    private List<RecentRepo> recentRepos = new ArrayList<RecentRepo>();

    public static Map<String, String> defaultShortcuts() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("refresh", "F5");
        map.put("fetch", "Ctrl+Shift+F");
        map.put("push", "Ctrl+Shift+P");
        return map;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Map<String, String> getShortcuts() {
        return shortcuts;
    }

    public void setShortcuts(Map<String, String> shortcuts) {
        this.shortcuts = shortcuts == null ? defaultShortcuts() : shortcuts;
    }

    public List<RecentRepo> getRecentRepos() {
        return recentRepos;
    }

    public void setRecentRepos(List<RecentRepo> recentRepos) {
        this.recentRepos = recentRepos == null ? new ArrayList<RecentRepo>() : recentRepos;
    }
}
