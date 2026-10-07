package com.example.rag.document.config.index;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IndexTaskConfiguration {

    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    /** 创建有界索引线程池，避免任务堆积为无界内存队列。 */
    @Bean("documentIndexExecutor")
    public ThreadPoolTaskExecutor documentIndexExecutor(IndexTaskProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("document-index-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return executor;
    }
}
