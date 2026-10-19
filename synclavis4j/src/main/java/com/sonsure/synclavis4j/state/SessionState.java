package com.sonsure.synclavis4j.state;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态：已打开仓库 Tab 与激活项。持久化以后端为准。
 */
public class SessionState {

    private String activeId;
    private List<SessionTab> tabs = new ArrayList<SessionTab>();

    public String getActiveId() {
        return activeId;
    }

    public void setActiveId(String activeId) {
        this.activeId = activeId;
    }

    public List<SessionTab> getTabs() {
        return tabs;
    }

    public void setTabs(List<SessionTab> tabs) {
        this.tabs = tabs == null ? new ArrayList<SessionTab>() : tabs;
    }

    public SessionTab find(String repoId) {
        for (SessionTab tab : tabs) {
            if (tab.getId().equals(repoId)) {
                return tab;
            }
        }
        return null;
    }
}
