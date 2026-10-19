package com.sonsure.synclavis4j.git;

import com.sonsure.synclavis4j.auth.TransportAuth;
import com.sonsure.synclavis4j.auth.TransportAuthConfigurer;
import com.sonsure.synclavis4j.config.AsyncConfig;
import com.sonsure.synclavis4j.git.dto.OperationHandleDto;
import com.sonsure.synclavis4j.git.progress.WebSocketProgressMonitor;
import com.sonsure.synclavis4j.operation.OperationRegistry;
import com.sonsure.synclavis4j.repo.RepoLockRegistry;
import com.sonsure.synclavis4j.repo.RepoRepositoryManager;
import com.sonsure.synclavis4j.repo.RepoService;
import com.sonsure.synclavis4j.shared.exception.ErrorCode;
import com.sonsure.synclavis4j.shared.exception.GitOperationException;
import com.sonsure.synclavis4j.shared.util.RepoId;
import com.sonsure.synclavis4j.shared.util.TransportErrors;
import com.sonsure.synclavis4j.websocket.WebSocketSessionManager;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 克隆远程仓库。
 *
 * <p>克隆时目标仓库尚未打开、还没有 repoId，因此进度推送到<b>全局事件通道</b>
 * （{@code /ws/events}）。克隆成功后自动把该仓库登记进会话（等价于 open），
 * 前端收到 {@code done} 后刷新会话即可看到新 Tab。</p>
 */
@Service
public class GitCloneService extends AbstractGitService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);

    private final RepoLockRegistry locks;
    private final OperationRegistry operations;
    private final RepoEventPublisher events;
    private final TransportAuthConfigurer authConfigurer;
    private final Executor executor;

    public GitCloneService(RepoRepositoryManager repositories,
                           RepoService repoService,
                           RepoLockRegistry locks,
                           OperationRegistry operations,
                           RepoEventPublisher events,
                           TransportAuthConfigurer authConfigurer,
                           @Qualifier(AsyncConfig.GIT_OPERATION_EXECUTOR) Executor executor) {
        super(repositories, repoService);
        this.locks = locks;
        this.operations = operations;
        this.events = events;
        this.authConfigurer = authConfigurer;
        this.executor = executor;
    }

    public OperationHandleDto cloneAsync(String url, String path, String sshKeyPath,
                                         String httpsUsername, String httpsPassword) {
        if (url == null || url.trim().isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "远程 URL 不能为空");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "目标路径不能为空");
        }

        final String remoteUrl = url.trim();
        final String target = RepoId.canonicalize(path.trim());
        final File dir = new File(target);
        if (dir.exists() && dir.isFile()) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "目标路径已存在同名文件：" + target);
        }
        String[] existing = dir.list();
        if (existing != null && existing.length > 0) {
            throw new GitOperationException(ErrorCode.INVALID_PATH, "目标目录已存在且非空：" + target);
        }

        final String channel = WebSocketSessionManager.GLOBAL_REPO_ID;
        final Semaphore lock = locks.writeLock("clone:" + target);
        if (!lock.tryAcquire()) {
            throw new GitOperationException(ErrorCode.REPO_BUSY, "该路径正在克隆中");
        }

        final String operationId = operations.begin(channel, "clone");
        final String keyPath = sshKeyPath;
        final String username = httpsUsername;
        final String password = httpsPassword;

        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try {
                        doClone(remoteUrl, target, keyPath, username, password, operationId, channel);
                        ok = true;
                    } catch (GitOperationException e) {
                        operations.fail(operationId, e.getCode().name(), e.getMessage());
                        events.publishError(channel, operationId, e.getCode(), e.getMessage(), e.getDetails());
                    } catch (Exception e) {
                        log.warn("克隆失败 url={}", remoteUrl, e);
                        operations.fail(operationId, ErrorCode.INTERNAL.name(), String.valueOf(e.getMessage()));
                        events.publishError(channel, operationId, ErrorCode.INTERNAL,
                                String.valueOf(e.getMessage()), null);
                    } finally {
                        operations.finish(operationId);
                        lock.release();
                    }
                    events.publishDone(channel, operationId, ok);
                }
            });
        } catch (RuntimeException e) {
            lock.release();
            operations.finish(operationId);
            throw e;
        }
        return new OperationHandleDto(operationId);
    }

    private void doClone(String remoteUrl, String target, String sshKeyPath,
                         String httpsUsername, String httpsPassword,
                         String operationId, String channel) {
        events.publishLog(channel, operationId, "info", "clone " + remoteUrl + " -> " + target);

        ProgressMonitor monitor = new WebSocketProgressMonitor(channel, operationId, events,
                new WebSocketProgressMonitor.CancelSignal() {
                    @Override
                    public boolean isCancelled() {
                        return operations.isCancelled(operationId);
                    }
                });

        CloneCommand command = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(new File(target))
                .setProgressMonitor(monitor);
        authConfigurer.configure(command, new TransportAuth(sshKeyPath, httpsUsername, httpsPassword), remoteUrl);

        Git git = null;
        try {
            git = command.call();
        } catch (InvalidRemoteException e) {
            throw new GitOperationException(ErrorCode.REMOTE_ERROR,
                    "无效的远程地址：" + e.getMessage(), null, e);
        } catch (TransportException e) {
            boolean authFailure = TransportErrors.isAuthFailure(e);
            throw new GitOperationException(authFailure ? ErrorCode.AUTH_FAILED : ErrorCode.REMOTE_ERROR,
                    (authFailure ? "认证失败：" : "远程传输失败：") + e.getMessage(), null, e);
        } catch (GitAPIException e) {
            throw new GitOperationException(ErrorCode.REMOTE_ERROR,
                    "克隆失败：" + e.getMessage(), null, e);
        } finally {
            if (git != null) {
                git.close();
            }
        }

        // 克隆成功后登记进会话，前端刷新会话即可看到新 Tab
        repoService.open(target, sshKeyPath);
        events.publishLog(channel, operationId, "info", "克隆完成：" + target);
    }
}
