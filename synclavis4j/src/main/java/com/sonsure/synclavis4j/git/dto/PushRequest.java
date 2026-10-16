package com.sonsure.synclavis4j.git.dto;

/**
 * Push 请求：{@code { setUpstream?, tags?, remote?, branch? }}。
 *
 * <p>首期不提供强推（{@code --force} / {@code --force-with-lease}）。</p>
 */
public class PushRequest {

    private boolean setUpstream;
    private boolean tags;
    private String remote;
    private String branch;

    public boolean isSetUpstream() {
        return setUpstream;
    }

    public void setSetUpstream(boolean setUpstream) {
        this.setUpstream = setUpstream;
    }

    public boolean isTags() {
        return tags;
    }

    public void setTags(boolean tags) {
        this.tags = tags;
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
