package com.tianji.promotion.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * <p>
 * 优惠促销模块的配置类
 * </p>
 *
 * @author author
 * @since 2026-09-21
 */
@Slf4j
@Configuration
public class PromotionConfig {

    /**
     * 生成兑换码专用的线程池，供 {@code @Async("generateExchangeCodeExecutor")} 使用
     */
    @Bean
    public Executor generateExchangeCodeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 1.核心线程数
        executor.setCorePoolSize(2);
        // 2.最大线程数（注意：只有队列满了才会扩充到这么多）
        executor.setMaxPoolSize(5);
        // 3.队列容量
        executor.setQueueCapacity(200);
        // 4.线程名前缀，便于在日志里识别
        executor.setThreadNamePrefix("exchange-code-handler-");
        // 5.拒绝策略：不丢弃任务，由提交任务的线程自己执行，形成背压
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 6.显式初始化
        executor.initialize();
        return executor;
    }
}
