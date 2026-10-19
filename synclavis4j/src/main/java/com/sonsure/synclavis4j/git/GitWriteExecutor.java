package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.config.AsyncConfig;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.operation.OperationRegistry;
import com.sonsure.synclavis4j.repo.RepoLockRegistry;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.shared.util.TransportErrors;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 仓库写操作的统一执行器。
 *
 * <p>所有写操作（fetch/pull/push/stash/commit/stage…）都必须经此执行，以保证：</p>
 * <ol>
 *   <li>同一仓库写操作串行（repoId 级公平锁）；跨仓库可并行；</li>
 *   <li>rebase 中间态下拒绝其他写操作；</li>
 *   <li>operationId 登记、进度/日志/状态/错误事件统一推送。</li>
 * </ol>
 */
@Component
public class GitWriteExecutor {

    private static final Logger log = LoggerFactory.getLogger(GitWriteExecutor.class);

    /** rebase 中间态下禁止的操作（stage/unstage/commit 需放行以便解决冲突）。 */
    private static final java.util.Set<String> BLOCKED_DURING_REBASE =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "fetch", "pull", "push", "clone", "stash", "checkout"));

    private final RepoLockRegistry locks;
    private final OperationRegistry operations;
    private final RepoEventPublisher events;
    private final RepoService repoService;
    private final GitStatusService statusService;
    private final Executor executor;

    public GitWriteExecutor(RepoLockRegistry locks,
                            OperationRegistry operations,
                            RepoEventPublisher events,
                            RepoService repoService,
                            GitStatusService statusService,
                            @Qualifier(AsyncConfig.GIT_OPERATION_EXECUTOR) Executor executor) {
        this.locks = locks;
        this.operations = operations;
        this.events = events;
        this.repoService = repoService;
        this.statusService = statusService;
        this.executor = executor;
    }

    public interface SyncTask<T> {
        T run(String operationId) throws Exception;
    }

    public interface AsyncTask {
        void run(String operationId) throws Exception;
    }

    /** 同步执行（本地快速操作：stash / stage / commit）。 */
    public <T> T runSync(String repoId, String kind, SyncTask<T> task) {
        repoService.pathOf(repoId);            // 未打开 → 404
        checkNotRebasing(repoId, kind);

        Semaphore lock = locks.writeLock(repoId);
        if (!lock.tryAcquire()) {
            throw new GitOperationException(ErrorCode.REPO_BUSY, "仓库正在执行其他操作");
        }
        String operationId = operations.begin(repoId, kind);
        try {
            T result = task.run(operationId);
            operations.finish(operationId);
            publishStatusQuietly(repoId);
            return result;
        } catch (GitOperationException e) {
            operations.fail(operationId, e.getCode().name(), e.getMessage());
            events.publishError(repoId, operationId, e.getCode(), e.getMessage(), e.getDetails());
            throw e;
        } catch (Exception e) {
            operations.fail(operationId, ErrorCode.INTERNAL.name(), String.valueOf(e.getMessage()));
            throw new GitOperationException(ErrorCode.INTERNAL, String.valueOf(e.getMessage()), null, e);
        } finally {
            lock.release();
        }
    }

    /** 异步执行（长操作：fetch / pull / push / clone）。立即返回 operationId。 */
    public OperationHandleDto runAsync(final String repoId, final String kind, final AsyncTask task) {
        repoService.pathOf(repoId);
        checkNotRebasing(repoId, kind);

        final Semaphore lock = locks.writeLock(repoId);
        if (!lock.tryAcquire()) {
            throw new GitOperationException(ErrorCode.REPO_BUSY, "仓库正在执行其他操作");
        }

        final String operationId = operations.begin(repoId, kind);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try {
                        task.run(operationId);
                        publishStatusQuietly(repoId);
                        ok = true;
                    } catch (GitOperationException e) {
                        operations.fail(operationId, e.getCode().name(), e.getMessage());
                        events.publishError(repoId, operationId, e.getCode(), e.getMessage(), e.getDetails());
                        publishStatusQuietly(repoId);
                    } catch (Exception e) {
                        // 取消 / 传输 / 认证 分类，避免一律报 500 INTERNAL
                        ErrorCode code = classify(e);
                        if (code == ErrorCode.CANCELLED) {
                            log.info("操作已被取消 repoId={} kind={}", repoId, kind);
                        } else if (code == ErrorCode.INTERNAL) {
                            log.warn("异步操作失败 repoId={} kind={}", repoId, kind, e);
                        } else {
                            log.info("操作失败 repoId={} kind={} code={} msg={}",
                                    repoId, kind, code, e.getMessage());
                        }
                        operations.fail(operationId, code.name(), String.valueOf(e.getMessage()));
                        events.publishError(repoId, operationId, code,
                                String.valueOf(e.getMessage()), null);
                        publishStatusQuietly(repoId);
                    } finally {
                        operations.finish(operationId);
                        // 必须先释放写锁再通知 done：否则客户端收到 done 立即发起下一个
                        // 写操作时会撞上尚未释放的锁，误报 REPO_BUSY。
                        lock.release();
                    }
                    events.publishDone(repoId, operationId, ok);
                }
            });
        } catch (RuntimeException e) {
            lock.release();
            operations.finish(operationId);
            throw e;
        }
        return new OperationHandleDto(operationId);
    }

    /**
     * rebase 中间态守卫。
     *
     * <p>中间态下禁止 fetch / pull / push / clone / stash；
     * 但 stage / unstage / commit 必须放行 —— 解决 rebase 冲突后需要先暂存已解决文件，
     * 再执行 Continue。rebase 自身的控制操作（kind 以 {@code rebase} 开头）始终放行。</p>
     */
    public void checkNotRebasing(String repoId, String kind) {
        if (kind == null || kind.startsWith("rebase")) {
            return;
        }
        if (!BLOCKED_DURING_REBASE.contains(kind)) {
            return;
        }
        com.sonsure.synclavis4j.git.dto.RepoStatusDto status = statusService.status(repoId);
        if (status.isRebaseInProgress()) {
            throw new GitOperationException(ErrorCode.REBASE_IN_PROGRESS,
                    "仓库处于 rebase 中间态，请先 Continue / Skip / Abort");
        }
    }

    private void publishStatusQuietly(String repoId) {
        try {
            events.publishStatus(repoId, statusService.status(repoId));
        } catch (RuntimeException e) {
            log.debug("推送状态失败 repoId={}", repoId, e);
        }
    }

    /** 异常分类：取消 / 认证失败 / 远程错误 / 其他内部错误。 */
    private ErrorCode classify(Throwable e) {
        if (TransportErrors.isCancellation(e)) {
            return ErrorCode.CANCELLED;
        }
        if (TransportErrors.isTransport(e)) {
            return TransportErrors.isAuthFailure(e) ? ErrorCode.AUTH_FAILED : ErrorCode.REMOTE_ERROR;
        }
        return ErrorCode.INTERNAL;
    }
}
