package com.sonsure.synclavis4j.git.dto;

/**
 * 分支切换请求：{@code { branch, create?, startPoint? }}（契约见《详细设计文档》§6.2）。
 *
 * <p>{@code create=false}（默认）时切换到已存在的本地分支 {@code branch}；
 * {@code create=true} 时以 {@code startPoint} 为起点新建 {@code branch} 并切换，
 * {@code startPoint} 省略表示当前 HEAD，可以是本地分支 / 远程跟踪名（如 {@code origin/dev}）/ commit id。</p>
 */
public class CheckoutRequest {

    private String branch;
    private boolean create;
    private String startPoint;

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public boolean isCreate() {
        return create;
    }

    public void setCreate(boolean create) {
        this.create = create;
    }

    public String getStartPoint() {
        return startPoint;
    }

    public void setStartPoint(String startPoint) {
        this.startPoint = startPoint;
    }
}
