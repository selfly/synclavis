package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.CommitResultDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.util.List;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;

/**
 * 提交操作（含 Amend）。首期不做 GPG 签名。
 *
 * <p>在 rebase 中间态下允许提交（部分冲突解决流程需要）。</p>
 */
@Service
public class GitCommitService extends AbstractGitService {

    private final GitWriteExecutor writeExecutor;

    public GitCommitService(RepoRepositoryManager repositories, RepoService repoService,
                            GitWriteExecutor writeExecutor) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
    }

    public CommitResultDto commit(final String repoId, final String message,
                                  final boolean amend, final List<String> files) {
        if (message == null || message.trim().isEmpty()) {
            throw new GitOperationException(ErrorCode.NOTHING_TO_COMMIT, "提交信息不能为空");
        }
        return writeExecutor.runSync(repoId, "commit", new GitWriteExecutor.SyncTask<CommitResultDto>() {
            @Override
            public CommitResultDto run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    CommitCommand command = git.commit()
                            .setMessage(message)
                            .setAmend(amend);
                    if (files != null) {
                        for (String file : files) {
                            command.setOnly(file);
                        }
                    }
                    RevCommit commit = command.call();
                    return new CommitResultDto(commit.getName(), commit.getShortMessage());
                } catch (EmptyCommitException e) {
                    throw new GitOperationException(ErrorCode.NOTHING_TO_COMMIT, "没有可提交的变更", null, e);
                } catch (GitAPIException e) {
                    throw new GitOperationException(ErrorCode.INTERNAL, "提交失败：" + e.getMessage(), null, e);
                }
            }
        });
    }
}
