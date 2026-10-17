package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.auth.TransportAuth;
import com.sonsure.synclavis4j.auth.TransportAuthConfigurer;
import com.sonsure.synclavis4j.git.dto.FetchRequest;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.git.dto.PullRequest;
import com.sonsure.synclavis4j.git.dto.PushRequest;
import com.sonsure.synclavis4j.git.progress.WebSocketProgressMonitor;
import com.sonsure.synclavis4j.operation.OperationRegistry;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.state.RepoSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.springframework.stereotype.Service;

/**
 * Git 远程写操作：fetch / pull / push。
 *
 * <p>异步模型：立即返回 operationId，实际执行在专用线程池；
 * 锁、中间态守卫与事件推送统一委托给 {@link GitWriteExecutor}。</p>
 */
@Service
public class GitOperationService extends AbstractGitService {

    private final GitWriteExecutor writeExecutor;
    private final OperationRegistry operations;
    private final RepoEventPublisher events;
    private final TransportAuthConfigurer authConfigurer;

    public GitOperationService(RepoRepositoryManager repositories,
                               RepoService repoService,
                               GitWriteExecutor writeExecutor,
                               OperationRegistry operations,
                               RepoEventPublisher events,
                               TransportAuthConfigurer authConfigurer) {
        super(repositories, repoService);
        this.writeExecutor = writeExecutor;
        this.operations = operations;
        this.events = events;
        this.authConfigurer = authConfigurer;
    }

    /** 按仓库设置解析认证（SSH 私钥路径 / HTTPS 用户名+口令）。 */
    private TransportAuth authOf(String repoId) {
        RepoSettings settings = repoService.repoSettingsEntity(repoId);
        return new TransportAuth(settings.getSshKeyPath(),
                settings.getHttpsUsername(), settings.getHttpsPassword());
    }

    private String remoteUrl(Repository repository, String remote) {
        return repository.getConfig().getString("remote", remote, "url");
    }

    public OperationHandleDto fetchAsync(final String repoId, final FetchRequest request) {
        final FetchRequest req = request == null ? new FetchRequest() : request;
        return writeExecutor.runAsync(repoId, "fetch", new GitWriteExecutor.AsyncTask() {
            @Override
            public void run(String operationId) throws Exception {
                doFetch(repoId, req, operationId);
            }
        });
    }

    public OperationHandleDto pullAsync(final String repoId, final PullRequest request) {
        final PullRequest req = request == null ? new PullRequest() : request;
        return writeExecutor.runAsync(repoId, "pull", new GitWriteExecutor.AsyncTask() {
            @Override
            public void run(String operationId) throws Exception {
                doPull(repoId, req, operationId);
            }
        });
    }

    public OperationHandleDto pushAsync(final String repoId, final PushRequest request) {
        final PushRequest req = request == null ? new PushRequest() : request;
        return writeExecutor.runAsync(repoId, "push", new GitWriteExecutor.AsyncTask() {
            @Override
            public void run(String operationId) throws Exception {
                doPush(repoId, req, operationId);
            }
        });
    }

    // ------------------------------------------------------------------ fetch

    private void doFetch(String repoId, FetchRequest req, String operationId)
            throws GitAPIException, IOException {
        Repository repository = repo(repoId);
        ProgressMonitor monitor = monitor(repoId, operationId);
        try (Git git = Git.wrap(repository)) {
            Set<String> targets = new LinkedHashSet<String>();
            if (req.isAll()) {
                targets.addAll(repository.getRemoteNames());
            } else {
                targets.add(resolveRemote(repository, req.getRemote(), repository.getBranch()));
            }
            if (targets.isEmpty()) {
                throw new GitOperationException(ErrorCode.NO_UPSTREAM, "仓库没有配置远程");
            }
            for (String remote : targets) {
                events.publishLog(repoId, operationId, "info", "fetch " + remote);
                FetchCommand command = git.fetch()
                        .setRemote(remote)
                        .setRemoveDeletedRefs(req.isPrune())
                        .setTagOpt(req.isTags() ? TagOpt.FETCH_TAGS : TagOpt.AUTO_FOLLOW)
                        .setProgressMonitor(monitor);
                authConfigurer.configure(command, authOf(repoId), remoteUrl(repository, remote));
                command.call();
            }
        }
    }

    // ------------------------------------------------------------------ pull

    private void doPull(String repoId, PullRequest req, String operationId)
            throws GitAPIException, IOException {
        Repository repository = repo(repoId);
        ProgressMonitor monitor = monitor(repoId, operationId);
        String branch = req.getBranch() != null && !req.getBranch().isEmpty()
                ? req.getBranch() : repository.getBranch();
        String remote = resolveRemote(repository, req.getRemote(), branch);
        String strategy = req.getStrategy();

        try (Git git = Git.wrap(repository)) {
            if (PullRequest.STRATEGY_REBASE.equals(strategy)) {
                ensureCleanForRebase(repository);
                events.publishLog(repoId, operationId, "info", "fetch " + remote);
                FetchCommand fetch = git.fetch().setRemote(remote).setProgressMonitor(monitor);
                authConfigurer.configure(fetch, authOf(repoId), remoteUrl(repository, remote));
                fetch.call();

                String upstream = resolveUpstream(repository, branch);
                events.publishLog(repoId, operationId, "info", "rebase onto " + upstream);
                RebaseResult result = git.rebase()
                        .setUpstream(upstream)
                        .setProgressMonitor(monitor)
                        .call();
                RebaseResult.Status status = result.getStatus();
                if (status == RebaseResult.Status.STOPPED || status == RebaseResult.Status.CONFLICTS) {
                    throw new GitOperationException(ErrorCode.REBASE_CONFLICT,
                            "rebase 存在冲突，请解决后 Continue 或 Abort",
                            new ArrayList<String>(conflictFiles(repository)));
                }
                if (status == RebaseResult.Status.UNCOMMITTED_CHANGES) {
                    throw new GitOperationException(ErrorCode.WORKTREE_NOT_CLEAN,
                            "rebase 需要干净的工作区，请先 commit 或 stash");
                }
                if (!isRebaseSuccessful(status)) {
                    throw new GitOperationException(ErrorCode.REBASE_FAILED, "rebase 失败：" + status);
                }
                events.publishLog(repoId, operationId, "info", "rebase 完成：" + status);
                return;
            }

            boolean ffOnly = PullRequest.STRATEGY_FF_ONLY.equals(strategy);
            PullCommand command = git.pull().setRemote(remote).setProgressMonitor(monitor);
            if (ffOnly) {
                command.setFastForward(MergeCommand.FastForwardMode.FF_ONLY);
            }
            authConfigurer.configure(command, authOf(repoId), remoteUrl(repository, remote));
            PullResult pullResult = command.call();
            assertPullSucceeded(pullResult, repository, ffOnly);
        } catch (CheckoutConflictException e) {
            throw new GitOperationException(ErrorCode.MERGE_CONFLICT,
                    "合并存在冲突，请解决后提交或放弃",
                    GitOperationException.conflictDetails(conflictFiles(repository)), e);
        }
    }

    /**
     * 校验 pull 结果。
     *
     * <p>注意：JGit 的 {@code pull()} 在合并冲突或无法快进时<b>不抛异常</b>，
     * 而是返回非成功的 {@link PullResult}（{@code isSuccessful() == false}）。</p>
     */
    private void assertPullSucceeded(PullResult result, Repository repository, boolean ffOnly) {
        if (result.isSuccessful()) {
            return;
        }
        MergeResult mergeResult = result.getMergeResult();
        MergeResult.MergeStatus status = mergeResult == null ? null : mergeResult.getMergeStatus();

        if (status == MergeResult.MergeStatus.CONFLICTING
                || status == MergeResult.MergeStatus.CHECKOUT_CONFLICT) {
            throw new GitOperationException(ErrorCode.MERGE_CONFLICT,
                    "合并存在冲突，请解决后提交或放弃",
                    GitOperationException.conflictDetails(conflictFiles(repository)));
        }
        if (ffOnly) {
            throw new GitOperationException(ErrorCode.PULL_NOT_FAST_FORWARD,
                    "无法快进（本地与远端已分叉），请改用 Merge 或 Rebase 策略");
        }
        throw new GitOperationException(ErrorCode.INTERNAL, "pull 未成功：" + status);
    }

    // ------------------------------------------------------------------ push

    private void doPush(String repoId, PushRequest req, String operationId)
            throws GitAPIException, IOException {
        Repository repository = repo(repoId);
        ProgressMonitor monitor = monitor(repoId, operationId);
        String branch = req.getBranch() != null && !req.getBranch().isEmpty()
                ? req.getBranch() : repository.getBranch();
        if (branch == null) {
            throw new GitOperationException(ErrorCode.INTERNAL, "处于分离 HEAD 状态，无法推送分支");
        }

        StoredConfig config = repository.getConfig();
        String configuredMerge = config.getString("branch", branch, "merge");
        boolean hasUpstream = configuredMerge != null;
        if (!hasUpstream && !req.isSetUpstream()) {
            throw new GitOperationException(ErrorCode.NO_UPSTREAM,
                    "当前分支未设置上游分支，请先设置 upstream");
        }

        String remote = resolveRemote(repository, req.getRemote(), branch);

        try (Git git = Git.wrap(repository)) {
            if (req.isSetUpstream()) {
                config.setString("branch", branch, "remote", remote);
                config.setString("branch", branch, "merge", "refs/heads/" + branch);
                config.save();
                events.publishLog(repoId, operationId, "info",
                        "已设置 upstream：" + remote + "/" + branch);
            }

            PushCommand command = git.push().setRemote(remote).setProgressMonitor(monitor);
            if (req.isTags()) {
                command.setPushTags();
            }
            if (req.isSetUpstream()) {
                command.setRefSpecs(new RefSpec(
                        "refs/heads/" + branch + ":refs/heads/" + branch));
            }

            events.publishLog(repoId, operationId, "info", "push " + remote + " " + branch);
            authConfigurer.configure(command, authOf(repoId), remoteUrl(repository, remote));
            Iterable<PushResult> results = command.call();
            assertPushSucceeded(results);
        }
    }

    /** 逐 ref 解析推送结果（首期不做强推，非快进直接给出引导）。 */
    private void assertPushSucceeded(Iterable<PushResult> results) {
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                RemoteRefUpdate.Status status = update.getStatus();
                if (status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE) {
                    continue;
                }
                if (status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                    throw new GitOperationException(ErrorCode.PUSH_REJECTED_NON_FAST_FORWARD,
                            "推送被拒绝：远端有新提交（非快进），请先 Pull");
                }
                if (status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED) {
                    throw new GitOperationException(ErrorCode.PUSH_REJECTED_STALE,
                            "推送被拒绝：远端引用已变更，请先 Fetch");
                }
                if (status == RemoteRefUpdate.Status.REJECTED_OTHER_REASON
                        || status == RemoteRefUpdate.Status.NON_EXISTING) {
                    String message = update.getMessage();
                    throw new GitOperationException(ErrorCode.REMOTE_ERROR,
                            "推送被拒绝：" + (message == null ? status.name() : message));
                }
            }
        }
    }

    // ------------------------------------------------------------------ 公共工具

    /** rebase 成功状态集合（JGit 的 RebaseResult.Status 没有 isSuccessful()）。 */
    private boolean isRebaseSuccessful(RebaseResult.Status status) {
        return status == RebaseResult.Status.OK
                || status == RebaseResult.Status.FAST_FORWARD
                || status == RebaseResult.Status.UP_TO_DATE
                || status == RebaseResult.Status.NOTHING_TO_COMMIT;
    }

    public void ensureCleanForRebase(Repository repository) throws GitAPIException {
        Status status = Git.wrap(repository).status().call();
        boolean dirty = !status.getAdded().isEmpty()
                || !status.getChanged().isEmpty()
                || !status.getRemoved().isEmpty()
                || !status.getModified().isEmpty()
                || !status.getMissing().isEmpty();
        if (dirty) {
            throw new GitOperationException(ErrorCode.WORKTREE_NOT_CLEAN,
                    "rebase 需要干净的工作区，请先 commit 或 stash");
        }
    }

    public List<String> conflictFiles(Repository repository) {
        try {
            return new ArrayList<String>(Git.wrap(repository).status().call().getConflicting());
        } catch (GitAPIException e) {
            return new ArrayList<String>();
        }
    }

    /** 解析当前分支的上游远程跟踪引用。 */
    private String resolveUpstream(Repository repository, String branch) {
        StoredConfig config = repository.getConfig();
        String merge = config.getString("branch", branch, "merge");
        String remote = config.getString("branch", branch, "remote");
        if (merge == null || remote == null) {
            throw new GitOperationException(ErrorCode.NO_UPSTREAM,
                    "当前分支未设置上游分支，请先设置 upstream");
        }
        return "refs/remotes/" + remote + "/" + Repository.shortenRefName(merge);
    }

    private String resolveRemote(Repository repository, String explicit, String branch) {
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }
        if (branch != null) {
            String configured = repository.getConfig().getString("branch", branch, "remote");
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        Set<String> remotes = repository.getRemoteNames();
        if (remotes.contains(Constants.DEFAULT_REMOTE_NAME)) {
            return Constants.DEFAULT_REMOTE_NAME;
        }
        if (remotes.isEmpty()) {
            throw new GitOperationException(ErrorCode.NO_UPSTREAM, "仓库没有配置远程");
        }
        return remotes.iterator().next();
    }

    private ProgressMonitor monitor(final String repoId, final String operationId) {
        return new WebSocketProgressMonitor(repoId, operationId, events,
                new WebSocketProgressMonitor.CancelSignal() {
                    @Override
                    public boolean isCancelled() {
                        return operations.isCancelled(operationId);
                    }
                });
    }
}
