package com.sonsure.synclavis4j.repo.dto;

/**
 * 克隆仓库请求：{@code { url, path, sshKeyPath?, httpsUsername?, httpsPassword? }}。
 *
 * <p>{@code httpsPassword} 仅在请求中出现，不会被回显或记录到日志。</p>
 */
public class CloneRepoRequest {

    private String url;
    private String path;
    private String sshKeyPath;
    private String httpsUsername;
    private String httpsPassword;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

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
