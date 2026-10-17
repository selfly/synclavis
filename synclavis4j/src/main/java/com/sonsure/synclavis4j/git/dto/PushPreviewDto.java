package com.sonsure.synclavis4j.git.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 推送预览：本次 push 会推上去的提交（见《详细设计文档》§6.2）。
 *
 * <p>只读同步查询，不过每仓库写锁；{@code commits} 至多 {@link #COMMIT_LIMIT} 条，
 * 前端按 {@code commits.size() < ahead} 提示「还有 N 条未列出」。</p>
 */
public class PushPreviewDto {

    /** 预览最多列出的提交数，超出只回 total（ahead） */
    public static final int COMMIT_LIMIT = 100;

    /** 实际使用的远程名 */
    private String remote;
    /** 要推送的本地分支名 */
    private String branch;
    /** 推送目标引用，恒为 {@code refs/heads/<branch>} */
    private String targetRef;
    /** 上游跟踪名（如 {@code origin/main}），未设置时为 {@code null} */
    private String upstream;
    /** 计算 {@code ahead} 用的基准引用；为 {@code null} 表示「不在任何远程分支上的提交」 */
    private String base;
    /** 待推送提交数 */
    private int ahead;
    /** 远端领先数；{@code > 0} 说明非快进，推送必被拒 */
    private int behind;
    /** 待推送提交（新→旧），至多 {@link #COMMIT_LIMIT} 条 */
    private List<CommitDto> commits = new ArrayList<CommitDto>();

    public String getRemote() {
        return remote;
    }

    public void setRemote(String remote) {
        this.remote = remote;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getTargetRef() {
        return targetRef;
    }

    public void setTargetRef(String targetRef) {
        this.targetRef = targetRef;
    }

    public String getUpstream() {
        return upstream;
    }

    public void setUpstream(String upstream) {
        this.upstream = upstream;
    }

    public String getBase() {
        return base;
    }

    public void setBase(String base) {
        this.base = base;
    }

    public int getAhead() {
        return ahead;
    }

    public void setAhead(int ahead) {
        this.ahead = ahead;
    }

    public int getBehind() {
        return behind;
    }

    public void setBehind(int behind) {
        this.behind = behind;
    }

    public List<CommitDto> getCommits() {
        return commits;
    }

    public void setCommits(List<CommitDto> commits) {
        this.commits = commits;
    }
}
