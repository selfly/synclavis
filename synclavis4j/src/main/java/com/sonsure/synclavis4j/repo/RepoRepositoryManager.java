package com.sonsure.synclavis4j.repo;

import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.shared.util.RepoId;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JGit {@link Repository} 实例缓存与生命周期管理。
 *
 * <p>避免频繁打开/关闭仓库的开销；关闭 Tab 或应用退出时显式 close()。</p>
 */
@Component
public class RepoRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(RepoRepositoryManager.class);

    private final Map<String, Repository> cache = new ConcurrentHashMap<String, Repository>();

    /**
     * 获取（必要时构建并缓存）仓库实例。同一 repoId 返回同一实例。
     */
    public Repository acquire(String repoId, String path) {
        Repository cached = cache.get(repoId);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cache.get(repoId);
            if (cached != null) {
                return cached;
            }
            Repository built = build(path);
            cache.put(repoId, built);
            return built;
        }
    }

    public Repository get(String repoId) {
        return cache.get(repoId);
    }

    public boolean isOpen(String repoId) {
        return cache.containsKey(repoId);
    }

    public void release(String repoId) {
        Repository repository = cache.remove(repoId);
        if (repository != null) {
            repository.close();
            log.debug("已释放仓库实例 repoId={}", repoId);
        }
    }

    public List<String> openRepoIds() {
        return new ArrayList<String>(cache.keySet());
    }

    @PreDestroy
    public void closeAll() {
        for (Map.Entry<String, Repository> entry : cache.entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException e) {
                log.debug("关闭仓库实例失败 repoId={}", entry.getKey(), e);
            }
        }
        cache.clear();
    }

    /**
     * 仅做校验（是否可打开为 Git 仓库），不缓存。
     */
    public void validate(String path) {
        Repository repository = build(path);
        repository.close();
    }

    private Repository build(String path) {
        File dir = new File(RepoId.canonicalize(path));
        if (!dir.exists()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不存在：" + dir.getAbsolutePath());
        }
        if (!dir.isDirectory()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "不是目录：" + dir.getAbsolutePath());
        }

        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.setWorkTree(dir);
        builder.findGitDir(dir);
        if (builder.getGitDir() == null) {
            throw new GitOperationException(ErrorCode.NOT_A_REPO, "不是 Git 仓库：" + dir.getAbsolutePath());
        }
        try {
            return builder.build();
        } catch (RepositoryNotFoundException e) {
            throw new GitOperationException(ErrorCode.NOT_A_REPO, "不是 Git 仓库：" + dir.getAbsolutePath(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "打开仓库失败：" + e.getMessage(), null, e);
        }
    }
}
