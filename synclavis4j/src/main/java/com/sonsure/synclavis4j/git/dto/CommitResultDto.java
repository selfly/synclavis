package com.sonsure.synclavis4j.git.dto;

/**
 * 提交结果。
 */
public class CommitResultDto {

    private String id;
    private String subject;

    public CommitResultDto() {
    }

    public CommitResultDto(String id, String subject) {
        this.id = id;
        this.subject = subject;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
}
