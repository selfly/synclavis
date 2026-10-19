package com.sonsure.synclavis4j.auth;

import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.springframework.stereotype.Component;

/**
 * 把认证信息注入到 JGit 的传输命令上。
 *
 * <ul>
 *   <li>SSH：经 {@link TransportConfigCallback} 注入 per-transport 的 {@link SshSessionFactory}
 *       （{@code Transport} 本身没有 setSshSessionFactory，必须 instanceof 到 {@link SshTransport}）</li>
 *   <li>HTTPS：{@code setCredentialsProvider(new UsernamePasswordCredentialsProvider(...))}</li>
 * </ul>
 */
@Component
public class TransportAuthConfigurer {

    private final SshSessionFactoryProvider sshSessionFactoryProvider;
    private final CredentialResolver credentialResolver;

    public TransportAuthConfigurer(SshSessionFactoryProvider sshSessionFactoryProvider,
                                   CredentialResolver credentialResolver) {
        this.sshSessionFactoryProvider = sshSessionFactoryProvider;
        this.credentialResolver = credentialResolver;
    }

    /** 依据远端 URL 协议注入认证；返回同一命令以便链式调用。 */
    public <C extends TransportCommand<?, ?>> C configure(C command, TransportAuth auth, String remoteUrl) {
        if (isSsh(remoteUrl)) {
            final SshSessionFactory factory =
                    sshSessionFactoryProvider.forKey(auth == null ? null : auth.getSshKeyPath());
            command.setTransportConfigCallback(new TransportConfigCallback() {
                @Override
                public void configure(Transport transport) {
                    if (transport instanceof SshTransport) {
                        ((SshTransport) transport).setSshSessionFactory(factory);
                    }
                }
            });
            return command;
        }

        CredentialsProvider provider = credentialResolver.resolve(auth);
        if (provider != null) {
            command.setCredentialsProvider(provider);
        }
        return command;
    }

    /** 是否为 SSH 远端（{@code ssh://...} 或 scp 风格 {@code git@host:path}）。 */
    public boolean isSsh(String remoteUrl) {
        if (remoteUrl == null) {
            return false;
        }
        String url = remoteUrl.trim();
        if (url.startsWith("ssh://")) {
            return true;
        }
        if (url.contains("://")) {
            return false;
        }
        // scp 风格：user@host:path
        int at = url.indexOf('@');
        int colon = url.indexOf(':');
        return at > 0 && colon > at;
    }
}
