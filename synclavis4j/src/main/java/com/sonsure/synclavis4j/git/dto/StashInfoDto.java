package com.sonsure.synclavis4j.git.dto;

/**
 * Stash 条目。索引 0 为最新。
 */
public class StashInfoDto {

    private int index;
    private String message;
    private String commitId;
    private String time;

    public StashInfoDto() {
    }

    public StashInfoDto(int index, String message, String commitId, String time) {
        this.index = index;
        this.message = message;
        this.commitId = commitId;
        this.time = time;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
