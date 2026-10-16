package com.sonsure.synclavis4j.git.dto;

/**
 * 标签信息。
 */
public class TagInfoDto {

    private String name;
    private String commitId;
    private String message;

    public TagInfoDto() {
    }

    public TagInfoDto(String name, String commitId, String message) {
        this.name = name;
        this.commitId = commitId;
        this.message = message;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
