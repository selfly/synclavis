package com.sonsure.synclavis4j.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 首版 JSON 文件实现：{@code ${synclavis.state.dir}/state.json}。
 *
 * <p>不引入外部数据库；所有写入原子替换。</p>
 */
@Component
public class JsonStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(JsonStateStore.class);

    private static final String FILE_NAME = "state.json";
    private static final String BACKUP_SUFFIX = ".bak";

    private final ObjectMapper mapper;
    private final Path file;
    private final Object writeLock = new Object();

    private volatile AppState cached;

    public JsonStateStore(ObjectMapper mapper,
                          @Value("${synclavis.state.dir}") String stateDir) {
        this.mapper = mapper.copy();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.file = Paths.get(stateDir).resolve(FILE_NAME);
    }

    @PostConstruct
    public void init() {
        // 首次启动落盘一份默认状态，便于用户查看与编辑
        save(load());
    }

    @Override
    public AppState load() {
        AppState existing = cached;
        if (existing != null) {
            return existing;
        }
        synchronized (writeLock) {
            if (cached != null) {
                return cached;
            }
            cached = readFromDisk();
            return cached;
        }
    }

    @Override
    public void save(AppState state) {
        synchronized (writeLock) {
            try {
                byte[] json = mapper.writeValueAsBytes(state);
                AtomicJsonWriter.write(file, json);
                restrictPermissions(file);
                cached = state;
            } catch (IOException e) {
                throw new IllegalStateException("状态持久化失败: " + file, e);
            }
        }
    }

    /**
     * 状态文件含 HTTPS 凭据等敏感信息，收紧为仅属主可读写（POSIX 环境下为 0600）。
     */
    private void restrictPermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.debug("当前文件系统不支持 POSIX 权限，跳过: {}", target);
        } catch (IOException e) {
            log.warn("收紧状态文件权限失败: {}", target, e);
        }
    }

    @Override
    public void updateSession(SessionState session) {
        synchronized (writeLock) {
            AppState state = load();
            state.setSession(session);
            save(state);
        }
    }

    @Override
    public void updateGlobalSettings(GlobalSettings settings) {
        synchronized (writeLock) {
            AppState state = load();
            state.setGlobalSettings(settings);
            save(state);
        }
    }

    @Override
    public void updateRepoSettings(String repoId, RepoSettings settings) {
        synchronized (writeLock) {
            AppState state = load();
            state.getRepos().put(repoId, settings);
            save(state);
        }
    }

    public Path getFile() {
        return file;
    }

    private AppState readFromDisk() {
        if (!Files.exists(file)) {
            log.info("状态文件不存在，使用默认状态: {}", file);
            return new AppState();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            AppState state = mapper.readValue(bytes, AppState.class);
            return state == null ? new AppState() : state;
        } catch (IOException e) {
            log.warn("状态文件损坏，已备份并回退默认状态: {}", file, e);
            backupBrokenFile();
            return new AppState();
        }
    }

    private void backupBrokenFile() {
        try {
            Files.move(file, file.resolveSibling(FILE_NAME + BACKUP_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            log.debug("备份损坏状态文件失败", ignored);
        }
    }
}
