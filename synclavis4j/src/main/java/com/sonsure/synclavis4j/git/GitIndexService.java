package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.List;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

/**
 * 暂存区操作：stage / unstage。
 *
 * <p>两者也走统一写锁；且在 rebase 中间态下<b>允许</b>执行（解决冲突后需要暂存）。</p>
 */
@Service
public class GitIndexService extends AbstractGitService {

    private final GitWriteExecutor writeExecutor;

    public GitIndexService(RepoRepositoryManager repositories, RepoService repoService,
                           GitWriteExecutor writeExecutor) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
    }

    public void stage(final String repoId, final List<String> paths) {
        validate(paths);
        writeExecutor.runSync(repoId, "stage", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    AddCommand command = git.add();
                    for (String path : paths) {
                        command.addFilepattern(path);
                    }
                    command.call();
                    return null;
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL, "暂存失败：" + e.getMessage(), null, e);
                }
            }
        });
    }

    /** 取消暂存：把指定路径重置回 HEAD 状态。 */
    public void unstage(final String repoId, final List<String> paths) {
        validate(paths);
        writeExecutor.runSync(repoId, "unstage", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    ResetCommand command = git.reset();
                    for (String path : paths) {
                        command.addPath(path);
                    }
                    command.call();
                    return null;
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL, "取消暂存失败：" + e.getMessage(), null, e);
                }
            }
        });
    }

    /** 路径校验：拒绝空值、绝对路径与目录穿越。 */
    private void validate(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "paths 不能为空");
        }
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "路径不能为空");
            }
            String trimmed = path.trim();
            if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains("..")) {
                throw new GitOperationException(ErrorCode.INVALID_PATH, "非法路径：" + trimmed);
            }
        }
    }
}
