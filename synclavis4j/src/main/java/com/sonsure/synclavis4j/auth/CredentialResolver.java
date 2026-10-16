package com.sonsure.synclavis4j.auth;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * HTTPS 凭据解析：用户名 + PAT/密码。
 *
 * <p>凭据按仓库配置保存于本地状态文件（仅本机可读，见 {@code JsonStateStore}）。
 * 若未配置用户名，返回 {@code null}，由 JGit 默认行为处理（通常报认证失败）。</p>
 */
@Component
public class CredentialResolver {

    public CredentialsProvider resolve(TransportAuth auth) {
        if (auth == null || auth.getHttpsUsername() == null || auth.getHttpsUsername().trim().isEmpty()) {
            return null;
        }
        String password = auth.getHttpsPassword() == null ? "" : auth.getHttpsPassword();
        return new UsernamePasswordCredentialsProvider(auth.getHttpsUsername().trim(), password);
    }
}
