package com.sonsure.synclavis4j.git.dto;

import java.util.List;

/**
 * 提交请求：{@code { message, amend?, files? }}。
 */
public class CommitRequest {

    private String message;
    private boolean amend;
    private List<String> files;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isAmend() {
        return amend;
    }

    public void setAmend(boolean amend) {
        this.amend = amend;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }
}
