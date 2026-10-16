package com.sonsure.synclavis4j.state;

/**
 * 单仓库设置。
 *
 * <p>包含 SSH 私钥路径与 HTTPS 凭据（用户名 + PAT/密码）。首期不支持私钥口令。
 * 本对象会被持久化到本地状态文件，因此 {@code httpsPassword} 属于敏感信息，
 * 只在写请求中接收，绝不出现在任何 API 响应里（见 {@code RepoSettingsDto}）。</p>
 */
public class RepoSettings {

    public static final String DEFAULT_PULL_STRATEGY = "merge";

    private String sshKeyPath;
    private String pullStrategy = DEFAULT_PULL_STRATEGY;
    private String httpsUsername;
    private String httpsPassword;

    public RepoSettings() {
    }

    public RepoSettings(String sshKeyPath, String pullStrategy) {
        this.sshKeyPath = sshKeyPath;
        this.pullStrategy = pullStrategy == null ? DEFAULT_PULL_STRATEGY : pullStrategy;
    }

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
