package com.sonsure.synclavis4j.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写工具：先写临时文件，再 {@code ATOMIC_MOVE} 覆盖目标，避免出现半截文件。
 */
public final class AtomicJsonWriter {

    private AtomicJsonWriter() {
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temp, content);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 少数文件系统不支持 ATOMIC_MOVE，退化为普通替换
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
