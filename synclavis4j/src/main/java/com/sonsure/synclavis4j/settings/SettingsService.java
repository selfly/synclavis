package com.sonsure.synclavis4j.settings;

import com.sonsure.synclavis4j.repo.dto.LayoutSettingsPatch;
import com.sonsure.synclavis4j.state.GlobalSettings;
import com.sonsure.synclavis4j.state.LayoutSettings;
import com.sonsure.synclavis4j.state.StateStore;
import org.springframework.stereotype.Service;

/**
 * 全局设置业务逻辑（主题、快捷键、最近仓库、布局尺寸）。后端持久化。
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
                                 java.util.List<com.sonsure.synclavis4j.state.RecentRepo> recentRepos,
                                 LayoutSettingsPatch layoutPatch) {
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
        if (layoutPatch != null) {
            // 逐个字段合并：只有非 null 的字段被覆盖，钳制在 LayoutSettings 的 setter 内完成
            LayoutSettings layout = settings.getLayout();
            if (layoutPatch.getCommitPanelHeight() != null) {
                layout.setCommitPanelHeight(layoutPatch.getCommitPanelHeight());
            }
            if (layoutPatch.getLogPanelHeight() != null) {
                layout.setLogPanelHeight(layoutPatch.getLogPanelHeight());
            }
            if (layoutPatch.getSidebarWidth() != null) {
                layout.setSidebarWidth(layoutPatch.getSidebarWidth());
            }
            if (layoutPatch.getCommitListWidth() != null) {
                layout.setCommitListWidth(layoutPatch.getCommitListWidth());
            }
        }
        stateStore.updateGlobalSettings(settings);
        return settings;
    }
}
