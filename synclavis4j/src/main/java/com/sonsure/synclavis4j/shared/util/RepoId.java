package com.sonsure.synclavis4j.shared.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 仓库标识：{@code sha1(realpath)} 取前 12 位（见《详细设计文档》附录 D / C3）。
 *
 * <p>同一仓库跨会话、跨后端实现都应得到相同 id，因此必须基于规范化绝对路径计算。</p>
 */
public final class RepoId {

    private static final int ID_LENGTH = 12;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RepoId() {
    }

    public static String of(String path) {
        String canonical = canonicalize(path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < hash.length && builder.length() < ID_LENGTH; i++) {
                builder.append(HEX[(hash[i] >> 4) & 0x0F]);
                if (builder.length() < ID_LENGTH) {
                    builder.append(HEX[hash[i] & 0x0F]);
                }
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** 规范化绝对路径：解析符号链接与 {@code ..}，保证同一目录得到同一 id。 */
    public static String canonicalize(String path) {
        File file = new File(path);
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
