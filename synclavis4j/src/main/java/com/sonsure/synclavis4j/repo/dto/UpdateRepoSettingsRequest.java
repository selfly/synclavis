package com.sonsure.synclavis4j.repo.dto;

/**
 * 更新仓库设置请求：{@code { sshKeyPath?, pullStrategy?, httpsUsername?, httpsPassword? }}。
 *
 * <p>{@code httpsPassword} 语义：{@code null} 表示不改动；空串表示清除；非空表示设置新值。</p>
 */
public class UpdateRepoSettingsRequest {

    private String sshKeyPath;
    private String pullStrategy;
    private String httpsUsername;
    private String httpsPassword;

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public void setSshKeyPath(String sshKeyPath) {
        this.sshKeyPath = sshKeyPath;
    }

    public String getPullStrategy() {
        return pullStrategy;
    }

    public void setPullStrategy(String pullStrategy) {
        this.pullStrategy = pullStrategy;
    }

    public String getHttpsUsername() {
        return httpsUsername;
    }

    public void setHttpsUsername(String httpsUsername) {
        this.httpsUsername = httpsUsername;
    }

    public String getHttpsPassword() {
        return httpsPassword;
    }

    public void setHttpsPassword(String httpsPassword) {
        this.httpsPassword = httpsPassword;
    }
}
