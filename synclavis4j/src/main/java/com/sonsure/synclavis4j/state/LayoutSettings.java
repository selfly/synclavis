package com.sonsure.synclavis4j.state;

/**
 * 前端布局尺寸（全局设置的一部分，后端持久化）。
 *
 * <p>与《详细设计文档》附录 B 的状态文件契约一一对应；前端 localStorage 仅作瞬时缓存。
 * 所有 setter 都做上下界钳制，因此即使状态文件被手工改成非法值，读出来也是安全的。</p>
 */
public class LayoutSettings {

    public static final int COMMIT_PANEL_MIN = 120;
    /** 仅作契约安全网；实际可用上限由前端按右列实测高度推导（右列高 - Diff 保底 - 分隔条） */
    public static final int COMMIT_PANEL_MAX = 2560;
    public static final int LOG_PANEL_MIN = 80;
    public static final int LOG_PANEL_MAX = 480;
    public static final int SIDEBAR_MIN = 120;
    public static final int SIDEBAR_MAX = 420;
    public static final int COMMIT_LIST_MIN = 240;
    public static final int COMMIT_LIST_MAX = 720;
    public static final int COMMIT_FILE_LIST_MIN = 140;
    /** 仅作契约安全网；实际可用上限由前端按面板宽度推导（面板宽 - 提交信息框保底） */
    public static final int COMMIT_FILE_LIST_MAX = 2560;

    public static final int DEFAULT_COMMIT_PANEL_HEIGHT = 190;
    public static final int DEFAULT_LOG_PANEL_HEIGHT = 150;
    public static final int DEFAULT_SIDEBAR_WIDTH = 240;
    public static final int DEFAULT_COMMIT_LIST_WIDTH = 380;
    public static final int DEFAULT_COMMIT_FILE_LIST_WIDTH = 260;

    private int commitPanelHeight = DEFAULT_COMMIT_PANEL_HEIGHT;
    private int logPanelHeight = DEFAULT_LOG_PANEL_HEIGHT;
    private int sidebarWidth = DEFAULT_SIDEBAR_WIDTH;
    private int commitListWidth = DEFAULT_COMMIT_LIST_WIDTH;
    private int commitFileListWidth = DEFAULT_COMMIT_FILE_LIST_WIDTH;

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public int getCommitPanelHeight() {
        return commitPanelHeight;
    }

    public void setCommitPanelHeight(int commitPanelHeight) {
        this.commitPanelHeight = clamp(commitPanelHeight, COMMIT_PANEL_MIN, COMMIT_PANEL_MAX);
    }

    public int getLogPanelHeight() {
        return logPanelHeight;
    }

    public void setLogPanelHeight(int logPanelHeight) {
        this.logPanelHeight = clamp(logPanelHeight, LOG_PANEL_MIN, LOG_PANEL_MAX);
    }

    public int getSidebarWidth() {
        return sidebarWidth;
    }

    public void setSidebarWidth(int sidebarWidth) {
        this.sidebarWidth = clamp(sidebarWidth, SIDEBAR_MIN, SIDEBAR_MAX);
    }

    public int getCommitListWidth() {
        return commitListWidth;
    }

    public void setCommitListWidth(int commitListWidth) {
        this.commitListWidth = clamp(commitListWidth, COMMIT_LIST_MIN, COMMIT_LIST_MAX);
    }

    public int getCommitFileListWidth() {
        return commitFileListWidth;
    }

    public void setCommitFileListWidth(int commitFileListWidth) {
        this.commitFileListWidth = clamp(commitFileListWidth, COMMIT_FILE_LIST_MIN, COMMIT_FILE_LIST_MAX);
    }
}
