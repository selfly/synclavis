package com.sonsure.synclavis4j.ai;

import com.sonsure.synclavis4j.repo.RepoService;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 执行线程池与仓库路径解析。
 *
 * <p>AI turn 使用独立线程池，避免长时间 prompt 占满 Git 操作线程池。</p>
 */
@Configuration
public class AiConfig {

    public static final String AI_TURN_EXECUTOR = "aiTurnExecutor";

    @Bean(name = AI_TURN_EXECUTOR)
    public Executor aiTurnExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("synclavis-ai-");
        executor.initialize();
        return executor;
    }

    @Bean
    public AiRepoPathResolver aiRepoPathResolver(RepoService repoService) {
        return repoService::pathOf;
    }
}
