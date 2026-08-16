package com.company.aics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池配置：为 SSE 流式问答等后台推送提供独立执行器。
 * 避免占用默认请求线程，保证流式输出可并发处理。
 */
@Configuration
public class AsyncConfig {

    /**
     * 流式输出专用线程池（核心 4、最大 8、队列 50）。
     *
     * @return 命名前缀为 {@code streaming-} 的任务执行器
     */
    @Bean
    @Primary
    TaskExecutor streamingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("streaming-");
        executor.initialize();
        return executor;
    }

    /**
     * 文档入库（向量化）专用线程池，上传接口可先返回 processing 再异步就绪/失败。
     */
    @Bean
    TaskExecutor documentIngestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.initialize();
        return executor;
    }
}
