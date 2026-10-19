package com.sonsure.synclavis4j.repo.dto;

import com.sonsure.synclavis4j.state.RepoSettings;

/**
 * 仓库设置的 API 视图。
 *
 * <p><b>安全约定</b>：{@code httpsPassword} 绝不回显，只返回 {@code hasHttpsPassword} 布尔标记。</p>
 */
public class RepoSettingsDto {

    private String sshKeyPath;
    private String pullStrategy;
    private String httpsUsername;
    private boolean hasHttpsPassword;

    public static RepoSettingsDto from(RepoSettings settings) {
        RepoSettingsDto dto = new RepoSettingsDto();
        dto.sshKeyPath = settings.getSshKeyPath();
        dto.pullStrategy = settings.getPullStrategy();
        dto.httpsUsername = settings.getHttpsUsername();
        dto.hasHttpsPassword = settings.getHttpsPassword() != null
                && !settings.getHttpsPassword().isEmpty();
        return dto;
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

    public boolean isHasHttpsPassword() {
        return hasHttpsPassword;
    }

    public void setHasHttpsPassword(boolean hasHttpsPassword) {
        this.hasHttpsPassword = hasHttpsPassword;
    }
}
