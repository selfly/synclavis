package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.git.dto.FileChangeDto;
import com.sonsure.synclavis4j.git.dto.RepoStatusDto;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.springframework.stereotype.Service;

/**
 * 仓库状态查询。
 */
@Service
public class GitStatusService extends AbstractGitService {

    public GitStatusService(RepoRepositoryManager repositories, RepoService repoService) {
        super(repositories, repoService);
    }

    public RepoStatusDto status(String repoId) {
        Repository repository = repo(repoId);
        RepoStatusDto dto = new RepoStatusDto();
        // Git.wrap 不接管 Repository 所有权，close() 不会关闭缓存的仓库实例
        try (Git git = Git.wrap(repository)) {
            Status status = git.status().call();

            String fullBranch = repository.getFullBranch();
            boolean detached = fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS);
            dto.setDetached(detached);
            dto.setBranch(detached ? null : repository.getBranch());

            // 暂存区变更
            List<FileChangeDto> staged = new ArrayList<FileChangeDto>();
            for (String path : status.getAdded()) {
                staged.add(new FileChangeDto(path, "ADD"));
            }
            for (String path : status.getChanged()) {
                staged.add(new FileChangeDto(path, "MODIFY"));
            }
            for (String path : status.getRemoved()) {
                staged.add(new FileChangeDto(path, "DELETE"));
            }
            dto.setStaged(staged);

            // 工作区变更
            List<FileChangeDto> unstaged = new ArrayList<FileChangeDto>();
            for (String path : status.getModified()) {
                unstaged.add(new FileChangeDto(path, "MODIFY"));
            }
            for (String path : status.getMissing()) {
                unstaged.add(new FileChangeDto(path, "DELETE"));
            }
            dto.setUnstaged(unstaged);

            dto.setUntracked(new ArrayList<String>(status.getUntracked()));
            dto.setConflicts(new ArrayList<String>(status.getConflicting()));
            dto.setRemotes(new ArrayList<String>(repository.getRemoteNames()));

            if (!detached) {
                String branch = repository.getBranch();
                BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
                if (tracking != null) {
                    dto.setHasUpstream(true);
                    dto.setAhead(tracking.getAheadCount());
                    dto.setBehind(tracking.getBehindCount());
                    dto.setUpstream(tracking.getRemoteTrackingBranch());
                }
            }

            RepositoryState state = repository.getRepositoryState();
            dto.setRepositoryState(state.name());
            dto.setRebaseInProgress(isRebasing(state));
            return dto;
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取仓库状态失败：" + e.getMessage(), null, e);
        } catch (IOException e) {
            throw new GitOperationException(ErrorCode.INTERNAL, "读取仓库状态失败：" + e.getMessage(), null, e);
        }
    }

    /**
     * 是否处于 rebase 中间态。
     *
     * <p>用 {@link RepositoryState} 判定，不检查 rebase-merge / rebase-apply 目录。
     * 注意 JGit 5.x 的枚举名：{@code REBASING} / {@code REBASING_REBASING} /
     * {@code REBASING_MERGE} / {@code REBASING_INTERACTIVE}
     * （并不存在 {@code REBASING_REBASE} 这个名字）。</p>
     */
    public static boolean isRebasing(RepositoryState state) {
        return state == RepositoryState.REBASING
                || state == RepositoryState.REBASING_REBASING
                || state == RepositoryState.REBASING_MERGE
                || state == RepositoryState.REBASING_INTERACTIVE;
    }
}
