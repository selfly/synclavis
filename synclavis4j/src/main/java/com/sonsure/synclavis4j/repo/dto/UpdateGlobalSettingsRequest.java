package com.sonsure.synclavis4j.repo.dto;

import com.sonsure.synclavis4j.state.RecentRepo;
import java.util.List;
import java.util.Map;

/**
 * 更新全局设置请求：字段均可选，仅更新传入项。
 */
public class UpdateGlobalSettingsRequest {

    private String theme;
    private Map<String, String> shortcuts;
    private List<RecentRepo> recentRepos;
    private LayoutSettingsPatch layout;

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
        this.shortcuts = shortcuts;
    }

    public List<RecentRepo> getRecentRepos() {
        return recentRepos;
    }

    public void setRecentRepos(List<RecentRepo> recentRepos) {
        this.recentRepos = recentRepos;
    }

    public LayoutSettingsPatch getLayout() {
        return layout;
    }

    public void setLayout(LayoutSettingsPatch layout) {
        this.layout = layout;
    }
}
