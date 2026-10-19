package com.sonsure.synclavis4j.git.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交历史分页结果。
 */
public class LogResponseDto {

    private int total;
    private List<CommitDto> commits = new ArrayList<CommitDto>();

    public LogResponseDto() {
    }

    public LogResponseDto(int total, List<CommitDto> commits) {
        this.total = total;
        this.commits = commits;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public List<CommitDto> getCommits() {
        return commits;
    }

    public void setCommits(List<CommitDto> commits) {
        this.commits = commits;
    }
}
