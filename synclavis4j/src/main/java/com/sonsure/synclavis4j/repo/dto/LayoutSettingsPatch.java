package com.sonsure.synclavis4j.repo.dto;

/**
 * 更新全局设置中的布局尺寸：字段均为可空，仅更新传入项（与 theme/shortcuts 同语义）。
 *
 * <p>用装箱类型而非 int，才能区分「未传」与「传 0」。</p>
 */
public class LayoutSettingsPatch {

    private Integer commitPanelHeight;
    private Integer logPanelHeight;
    private Integer sidebarWidth;
    private Integer commitListWidth;

    public Integer getCommitPanelHeight() {
        return commitPanelHeight;
    }

    public void setCommitPanelHeight(Integer commitPanelHeight) {
        this.commitPanelHeight = commitPanelHeight;
    }

    public Integer getLogPanelHeight() {
        return logPanelHeight;
    }

    public void setLogPanelHeight(Integer logPanelHeight) {
        this.logPanelHeight = logPanelHeight;
    }

    public Integer getSidebarWidth() {
        return sidebarWidth;
    }

    public void setSidebarWidth(Integer sidebarWidth) {
        this.sidebarWidth = sidebarWidth;
    }

    public Integer getCommitListWidth() {
        return commitListWidth;
    }

    public void setCommitListWidth(Integer commitListWidth) {
        this.commitListWidth = commitListWidth;
    }
}
