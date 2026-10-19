package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.StashCreateRequest;
import com.sonsure.synclavis4j.git.dto.StashInfoDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.stereotype.Service;

/**
 * Stash：列表 / 创建 / Apply / Drop / Pop。
 *
 * <p>JGit 5.x 没有 {@code StashPopCommand}，Pop = Apply + Drop，且两步在同一把仓库写锁内完成。
 * 首期不支持 {@code --keep-index} 与 {@code --staged}（JGit 未提供）。</p>
 */
@Service
public class GitStashService extends AbstractGitService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final GitWriteExecutor writeExecutor;

    public GitStashService(RepoRepositoryManager repositories, RepoService repoService,
                           GitWriteExecutor writeExecutor) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
    }

    /** Stash 列表，索引 0 为最新。 */
    public List<StashInfoDto> list(String repoId) {
        Repository repository = repo(repoId);
        try (Git git = Git.wrap(repository)) {
            Collection<RevCommit> stashes = git.stashList().call();
            List<StashInfoDto> result = new ArrayList<StashInfoDto>();
            int index = 0;
            for (RevCommit commit : stashes) {
                result.add(new StashInfoDto(index, commit.getFullMessage(), commit.getName(),
                        formatTime(commit)));
                index++;
            }
            return result;
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取 Stash 列表失败：" + e.getMessage(), null, e);
        }
    }

    /** 创建 Stash（默认包含未跟踪文件，等价 {@code git stash push -u}）。 */
    public StashInfoDto create(String repoId, final StashCreateRequest request) {
        final StashCreateRequest req = request == null ? new StashCreateRequest() : request;
        return writeExecutor.runSync(repoId, "stash", new GitWriteExecutor.SyncTask<StashInfoDto>() {
            @Override
            public StashInfoDto run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    Status status = git.status().call();
                    boolean nothingToStash = status.getAdded().isEmpty()
                            && status.getChanged().isEmpty()
                            && status.getRemoved().isEmpty()
                            && status.getModified().isEmpty()
                            && status.getMissing().isEmpty()
                            && (!req.isIncludeUntracked() || status.getUntracked().isEmpty());
                    if (nothingToStash) {
                        throw new GitOperationException(ErrorCode.NOTHING_TO_COMMIT,
                                "工作区没有可 Stash 的变更");
                    }

                    String message = req.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "WIP: " + OffsetDateTime.now().format(TIME_FORMAT);
                    }

                    RevCommit commit = git.stashCreate()
                            .setIncludeUntracked(req.isIncludeUntracked())
                            .setWorkingDirectoryMessage(message)
                            .setIndexMessage(message)
                            .call();
                    if (commit == null) {
                        throw new GitOperationException(ErrorCode.NOTHING_TO_COMMIT,
                                "工作区没有可 Stash 的变更");
                    }
                    return new StashInfoDto(0, commit.getFullMessage(), commit.getName(),
                            formatTime(commit));
                }
            }
        });
    }

    public void apply(final String repoId, final int index) {
        writeExecutor.runSync(repoId, "stash", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    git.stashApply().setStashRef(stashRef(index)).call();
                    return null;
                } catch (StashApplyFailureException e) {
                    throw new GitOperationException(ErrorCode.STASH_CONFLICT,
                            "Stash 应用存在冲突", conflictFiles(repository), e);
                }
            }
        });
    }

    public void drop(final String repoId, final int index) {
        writeExecutor.runSync(repoId, "stash", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    git.stashDrop().setStashRef(index).call();
                    return null;
                }
            }
        });
    }

    /** Pop = Apply + Drop（同一把锁内）。Apply 冲突时不 drop。 */
    public void pop(final String repoId, final int index) {
        writeExecutor.runSync(repoId, "stash", new GitWriteExecutor.SyncTask<Void>() {
            @Override
            public Void run(String operationId) throws Exception {
                Repository repository = repo(repoId);
                try (Git git = Git.wrap(repository)) {
                    try {
                        git.stashApply().setStashRef(stashRef(index)).call();
                    } catch (StashApplyFailureException e) {
                        throw new GitOperationException(ErrorCode.STASH_CONFLICT,
                                "Stash Pop 存在冲突（已保留该 Stash）",
                                conflictFiles(repository), e);
                    }
                    git.stashDrop().setStashRef(index).call();
                    return null;
                }
            }
        });
    }

    private String stashRef(int index) {
        return "stash@{" + index + "}";
    }

    private List<String> conflictFiles(Repository repository) {
        try {
            return new ArrayList<String>(Git.wrap(repository).status().call().getConflicting());
        } catch (GitAPIException e) {
            return new ArrayList<String>();
        }
    }

    private String formatTime(RevCommit commit) {
        return OffsetDateTime
                .ofInstant(commit.getAuthorIdent().getWhen().toInstant(), ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }
}
