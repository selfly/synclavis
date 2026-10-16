package com.sonsure.synclavis4j.git.dto;

/**
 * Stash 创建请求：{@code { includeUntracked?, message? }}。
 *
 * <p>需求约定的默认行为等价 {@code git stash push -u}，即默认包含未跟踪文件。</p>
 */
public class StashCreateRequest {

    private boolean includeUntracked = true;
    private String message;

    public boolean isIncludeUntracked() {
        return includeUntracked;
    }

    public void setIncludeUntracked(boolean includeUntracked) {
        this.includeUntracked = includeUntracked;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
