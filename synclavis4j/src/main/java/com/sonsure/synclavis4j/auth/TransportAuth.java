package com.sonsure.synclavis4j.auth;

/**
 * 一次远程操作所需的认证信息。
 *
 * <p>首期：SSH 用私钥路径（默认 {@code ~/.ssh/id_rsa}），HTTPS 用用户名 + PAT/密码。
 * 私钥口令（passphrase）不支持。</p>
 */
public class TransportAuth {

    private final String sshKeyPath;
    private final String httpsUsername;
    private final String httpsPassword;

    public TransportAuth(String sshKeyPath, String httpsUsername, String httpsPassword) {
        this.sshKeyPath = sshKeyPath;
        this.httpsUsername = httpsUsername;
        this.httpsPassword = httpsPassword;
    }

    public static TransportAuth none() {
        return new TransportAuth(null, null, null);
    }

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public String getHttpsUsername() {
        return httpsUsername;
    }

    public String getHttpsPassword() {
        return httpsPassword;
    }
}
