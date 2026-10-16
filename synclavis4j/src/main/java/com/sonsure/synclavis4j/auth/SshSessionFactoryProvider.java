package com.sonsure.synclavis4j.auth;

import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按私钥路径提供 {@link SshSessionFactory}（Apache MINA SSHD）。
 *
 * <p>关键点：<b>不使用全局单例</b>（{@code SshSessionFactory.setInstance}），
 * 而是按密钥路径缓存工厂，并通过 {@code SshTransport.setSshSessionFactory} 逐次注入，
 * 这样多个仓库可以各自使用不同的私钥而互不串扰。</p>
 *
 * <p>口令不支持：遇到加密私钥时给出明确错误，而不是抛出难以理解的传输异常。</p>
 */
@Component
public class SshSessionFactoryProvider {

    private static final Logger log = LoggerFactory.getLogger(SshSessionFactoryProvider.class);

    private static final String OPENSSH_MAGIC = "openssh-key-v1";

    private final Map<String, SshSessionFactory> cache = new ConcurrentHashMap<String, SshSessionFactory>();

    public SshSessionFactory forKey(String sshKeyPath) {
        String key = (sshKeyPath == null || sshKeyPath.trim().isEmpty())
                ? defaultKeyPath()
                : new File(sshKeyPath.trim()).getAbsolutePath();

        SshSessionFactory existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        SshSessionFactory created = build(key);
        SshSessionFactory previous = cache.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    /** 默认私钥：优先 {@code ~/.ssh/id_rsa}，其次 {@code id_ed25519}。 */
    public String defaultKeyPath() {
        File sshDir = sshDirectory();
        File rsa = new File(sshDir, "id_rsa");
        if (rsa.isFile()) {
            return rsa.getAbsolutePath();
        }
        File ed25519 = new File(sshDir, "id_ed25519");
        if (ed25519.isFile()) {
            return ed25519.getAbsolutePath();
        }
        return rsa.getAbsolutePath();
    }

    public File sshDirectory() {
        return new File(FS.DETECTED.userHome(), ".ssh");
    }

    private SshSessionFactory build(final String keyPath) {
        final File keyFile = new File(keyPath);
        if (!keyFile.exists()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "SSH 私钥不存在：" + keyPath);
        }
        if (!keyFile.isFile() || !keyFile.canRead()) {
            throw new GitOperationException(ErrorCode.PERMISSION_DENIED, "SSH 私钥不可读：" + keyPath);
        }
        if (isEncryptedPrivateKey(keyFile)) {
            throw new GitOperationException(ErrorCode.SSH_KEY_PASSPHRASE_UNSUPPORTED,
                    "该私钥已加密（需要口令），首期不支持口令；请使用无口令私钥，例如："
                            + "ssh-keygen -p -f " + keyPath + " -N \"\"");
        }

        final File home = FS.DETECTED.userHome();
        final File sshDir = sshDirectory();
        SshdSessionFactory factory = new SshdSessionFactoryBuilder()
                .setHomeDirectory(home)
                .setSshDirectory(sshDir)
                .setPreferredAuthentications("publickey")
                // 只使用该仓库指定的那一把私钥
                .setDefaultIdentities(new Function<File, List<Path>>() {
                    @Override
                    public List<Path> apply(File dir) {
                        List<Path> identities = new ArrayList<Path>();
                        identities.add(keyFile.toPath());
                        return identities;
                    }
                })
                .build(null);

        log.debug("已为密钥 {} 构建 SSH 会话工厂", keyPath);
        return factory;
    }

    /**
     * 检测加密私钥。
     *
     * <ul>
     *   <li>OpenSSH 新格式：base64 正文中 {@code ciphername} 不为 {@code none} 即为加密</li>
     *   <li>传统 PEM：出现 {@code Proc-Type: 4,ENCRYPTED} 即为加密</li>
     * </ul>
     */
    boolean isEncryptedPrivateKey(File keyFile) {
        String content;
        try {
            byte[] bytes = Files.readAllBytes(keyFile.toPath());
            content = new String(bytes, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            return false;
        }
        if (content.contains("Proc-Type: 4,ENCRYPTED")) {
            return true;
        }
        if (!content.contains("BEGIN OPENSSH PRIVATE KEY")) {
            return false;
        }
        try {
            int begin = content.indexOf("BEGIN OPENSSH PRIVATE KEY");
            int bodyStart = content.indexOf('\n', begin) + 1;
            int end = content.indexOf("-----END", bodyStart);
            String body = content.substring(bodyStart, end).replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(body);
            // 结构：magic(15) + uint32 len + ciphername
            if (decoded.length < 15 + 4) {
                return false;
            }
            int cipherLen = ((decoded[15] & 0xFF) << 24) | ((decoded[16] & 0xFF) << 16)
                    | ((decoded[17] & 0xFF) << 8) | (decoded[18] & 0xFF);
            if (cipherLen <= 0 || 19 + cipherLen > decoded.length) {
                return false;
            }
            String cipher = new String(decoded, 19, cipherLen, StandardCharsets.US_ASCII);
            return !"none".equals(cipher);
        } catch (RuntimeException e) {
            log.debug("私钥加密检测失败，按未加密处理: {}", keyFile, e);
            return false;
        }
    }

    /** 兼容性保留：OpenSSH 格式魔数（供测试断言使用）。 */
    static String openSshMagic() {
        return OPENSSH_MAGIC;
    }
}
