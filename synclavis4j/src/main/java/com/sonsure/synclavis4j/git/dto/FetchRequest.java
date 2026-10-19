package com.sonsure.synclavis4j.git.dto;

/**
 * Fetch 请求：{@code { remote?, all?, prune?, tags? }}。
 */
public class FetchRequest {

    private String remote;
    private boolean all;
    private boolean prune;
    private boolean tags;

    public String getRemote() {
        return remote;
    }

    public void setRemote(String remote) {
        this.remote = remote;
    }

    public boolean isAll() {
        return all;
    }

    public void setAll(boolean all) {
        this.all = all;
    }

    public boolean isPrune() {
        return prune;
    }

    public void setPrune(boolean prune) {
        this.prune = prune;
    }

    public boolean isTags() {
        return tags;
    }

    public void setTags(boolean tags) {
        this.tags = tags;
    }
}
