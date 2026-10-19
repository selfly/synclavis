package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.RebaseStatusDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.ArrayList;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.springframework.stereotype.Service;

/**
 * Rebase 中间态控制：status / continue / skip / abort。
 *
 * <p>是否处于中间态一律用 {@link Repository#getRepositoryState()} 判定，
 * 不检查 {@code rebase-merge} / {@code rebase-apply} 目录。</p>
 */
@Service
public class GitRebaseService extends AbstractGitService {

    private final GitWriteExecutor writeExecutor;

    public GitRebaseService(RepoRepositoryManager repositories, RepoService repoService,
                            GitWriteExecutor writeExecutor) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
    }

    public RebaseStatusDto status(String repoId) {
        Repository repository = repo(repoId);
        RepositoryState state = repository.getRepositoryState();
        return new RebaseStatusDto(GitStatusService.isRebasing(state), state.name());
    }

    public void continueRebase(String repoId) {
        operation(repoId, RebaseCommand.Operation.CONTINUE, "rebase-continue");
    }

    public void skipRebase(String repoId) {
        operation(repoId, RebaseCommand.Operation.SKIP, "rebase-skip");
    }

    public void abortRebase(String repoId) {
        operation(repoId, RebaseCommand.Operation.ABORT, "rebase-abort");
    }

    private void operation(final String repoId, final RebaseCommand.Operation operation, String kind) {
        writeExecutor.runSync(repoId, kind, new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                if (operation != RebaseCommand.Operation.ABORT
                        && !GitStatusService.isRebasing(repository.getRepositoryState())) {
                    throw new GitOperationException(ErrorCode.REBASE_FAILED,
                            "当前不处于 rebase 中间态");
                }
                try (Git git = Git.wrap(repository)) {
                    RebaseResult result = git.rebase().setOperation(operation).call();
                    RebaseResult.Status status = result.getStatus();
                    if (status == RebaseResult.Status.STOPPED || status == RebaseResult.Status.CONFLICTS) {
                        throw new GitOperationException(ErrorCode.REBASE_CONFLICT,
                                "仍有冲突未解决，请解决并 stage 后再次 Continue",
                                new ArrayList<String>(conflictFiles(repository)));
                    }
                    if (operation == RebaseCommand.Operation.ABORT) {
                        return null;   // abort 的终态可能是 ABORTED，视为成功
                    }
                    if (!isSuccessful(status)) {
                        throw new GitOperationException(ErrorCode.REBASE_FAILED,
                                "rebase " + operation.name().toLowerCase() + " 未成功：" + status);
                    }
                    return null;
                } catch (WrongRepositoryStateException e) {
                    throw new GitOperationException(ErrorCode.REBASE_FAILED,
                            "当前不处于 rebase 中间态", null, e);
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.REBASE_FAILED,
                            "rebase 操作失败：" + e.getMessage(), null, e);
                }
            }
        });
    }

    private boolean isSuccessful(RebaseResult.Status status) {
        return status == RebaseResult.Status.OK
                || status == RebaseResult.Status.FAST_FORWARD
                || status == RebaseResult.Status.UP_TO_DATE
                || status == RebaseResult.Status.NOTHING_TO_COMMIT;
    }

    private java.util.List<String> conflictFiles(Repository repository) {
        try {
            return new ArrayList<String>(Git.wrap(repository).status().call().getConflicting());
        } catch (GitAPIException e) {
            return new ArrayList<String>();
        }
    }
}
