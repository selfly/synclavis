package com.sonsure.synclavis4j.git.progress;

import com.sonsure.synclavis4j.git.RepoEventPublisher;
import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * 把 JGit 进度推送到 WebSocket 的 {@link ProgressMonitor} 实现。
 *
 * <p>注意：{@code ProgressMonitor} 接口只有 5 个方法
 * （start / beginTask / update / endTask / isCancelled），
 * <b>没有</b> {@code showDuration}（那是 TextProgressMonitor 的成员，不属于接口）。</p>
 */
public class WebSocketProgressMonitor implements ProgressMonitor {

    /** 进度推送节流窗口：避免 update 高频刷屏。 */
    private static final long THROTTLE_MILLIS = 100L;

    private final String repoId;
    private final String operationId;
    private final RepoEventPublisher events;
    private final CancelSignal cancelSignal;

    private String currentTitle = "";
    private int totalWork;
    private int accumulated;
    private long lastPushAt;

    public WebSocketProgressMonitor(String repoId, String operationId,
                                    RepoEventPublisher events, CancelSignal cancelSignal) {
        this.repoId = repoId;
        this.operationId = operationId;
        this.events = events;
        this.cancelSignal = cancelSignal;
    }

    @Override
    public void start(int totalTasks) {
        // 任务数对前端无额外价值，不推送
    }

    @Override
    public void beginTask(String title, int totalWork) {
        this.currentTitle = title == null ? "" : title;
        this.totalWork = totalWork;
        this.accumulated = 0;
        this.lastPushAt = 0L;
        events.publishProgress(repoId, operationId, currentTitle, 0, totalWork);
    }

    @Override
    public void update(int completed) {
        accumulated += completed;
        long now = System.currentTimeMillis();
        if (now - lastPushAt >= THROTTLE_MILLIS) {
            lastPushAt = now;
            events.publishProgress(repoId, operationId, currentTitle, accumulated, totalWork);
        }
    }

    @Override
    public void endTask() {
        events.publishProgress(repoId, operationId, currentTitle, accumulated, totalWork);
    }

    @Override
    public boolean isCancelled() {
        return cancelSignal != null && cancelSignal.isCancelled();
    }

    /** 取消信号来源（通常由 OperationRegistry 提供）。 */
    public interface CancelSignal {
        boolean isCancelled();
    }
}
