package com.sonsure.synclavis4j.repo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * 每仓库写锁：同一仓库写操作串行，跨仓库可并行。
 *
 * <p>这里刻意使用公平 {@link Semaphore}(1) 而不是 {@code ReentrantLock}：
 * 异步操作的加锁发生在 HTTP 请求线程、解锁发生在操作线程池线程，而
 * {@code ReentrantLock} 由持有线程独占，跨线程 {@code unlock()} 会抛
 * {@code IllegalMonitorStateException} 并导致锁永远无法释放。
 * 信号量不绑定线程，正好满足"请求线程加锁 / 工作线程解锁"的模型。</p>
 *
 * <p>前端互斥只是体验层，最终一致性由本锁保证。</p>
 */
@Component
public class RepoLockRegistry {

    private final Map<String, Semaphore> locks = new ConcurrentHashMap<String, Semaphore>();

    /** 公平信号量：避免长任务持续抢占。 */
    public Semaphore writeLock(String repoId) {
        Semaphore existing = locks.get(repoId);
        if (existing != null) {
            return existing;
        }
        Semaphore created = new Semaphore(1, true);
        Semaphore previous = locks.putIfAbsent(repoId, created);
        return previous != null ? previous : created;
    }

    public void forget(String repoId) {
        locks.remove(repoId);
    }
}
