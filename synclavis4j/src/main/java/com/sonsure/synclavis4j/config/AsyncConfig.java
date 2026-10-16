package com.sonsure.synclavis4j.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 长操作（clone / fetch / pull / push）线程池。
 *
 * <p>接口收到请求后立即返回 operationId，实际执行在此线程池中，
 * 进度通过 WebSocket 推送。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String GIT_OPERATION_EXECUTOR = "gitOperationExecutor";

    @Bean(name = GIT_OPERATION_EXECUTOR)
    public Executor gitOperationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("synclavis-op-");
        executor.initialize();
        return executor;
    }
}
