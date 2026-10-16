package com.sonsure.synclavis4j.git.dto;

/**
 * Rebase 中间态状态：{@code { inProgress, state }}。
 *
 * <p>{@code state} 取 JGit {@code RepositoryState} 的枚举名，rebase 相关取值为
 * {@code REBASING} / {@code REBASING_REBASING} / {@code REBASING_MERGE} / {@code REBASING_INTERACTIVE}。</p>
 */
public class RebaseStatusDto {

    private boolean inProgress;
    private String state;

    public RebaseStatusDto() {
    }

    public RebaseStatusDto(boolean inProgress, String state) {
        this.inProgress = inProgress;
        this.state = state;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public void setInProgress(boolean inProgress) {
        this.inProgress = inProgress;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
