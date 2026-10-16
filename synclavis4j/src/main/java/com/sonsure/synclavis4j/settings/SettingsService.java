package com.sonsure.synclavis4j.settings;

import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.StateStore;
import org.springframework.stereotype.Service;

/**
 * 全局设置业务逻辑（主题、快捷键、最近仓库）。后端持久化。
 */
@Service
public class SettingsService {

    private final StateStore stateStore;

    public SettingsService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    public GlobalSettings get() {
        return stateStore.load().getGlobalSettings();
    }

    public GlobalSettings update(String theme, java.util.Map<String, String> shortcuts,
                                 java.util.List<com.sonsure.synclavis4j.state.RecentRepo> recentRepos) {
        GlobalSettings settings = stateStore.load().getGlobalSettings();
        if (theme != null && !theme.trim().isEmpty()) {
            settings.setTheme(theme.trim());
        }
        if (shortcuts != null) {
            settings.setShortcuts(shortcuts);
        }
        if (recentRepos != null) {
            settings.setRecentRepos(recentRepos);
        }
        stateStore.updateGlobalSettings(settings);
        return settings;
    }
}
