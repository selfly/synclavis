package com.sonsure.synclavis4j.repo.dto;

/**
 * 更新激活 Tab 请求：{@code { repoId }}，{@code repoId} 为 null 表示无激活项。
 */
public class SetActiveRequest {

    private String repoId;

    public String getRepoId() {
        return repoId;
    }

    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }
}
