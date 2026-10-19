package com.sonsure.synclavis4j.repo.dto;

/**
 * 打开仓库请求：{@code { path, sshKeyPath? }}。
 */
public class OpenRepoRequest {

    private String path;
    private String sshKeyPath;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath;
    }
}
