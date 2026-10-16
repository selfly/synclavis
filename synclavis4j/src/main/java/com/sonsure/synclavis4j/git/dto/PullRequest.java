package com.sonsure.synclavis4j.git.dto;

/**
 * Pull 请求：{@code { strategy, remote?, branch? }}。
 *
 * <p>{@code strategy} ∈ {@code merge} / {@code rebase} / {@code ff-only}。</p>
 */
public class PullRequest {

    public static final String STRATEGY_MERGE = "merge";
    public static final String STRATEGY_REBASE = "rebase";
    public static final String STRATEGY_FF_ONLY = "ff-only";

    private String strategy = STRATEGY_MERGE;
    private String remote;
    private String branch;

    public String getStrategy() {
        return strategy == null ? STRATEGY_MERGE : strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

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
}
