package com.trading.algo.config;


import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async thread pool for @Async methods (e.g. OrbBacktestService.runBacktestAsync).
 *
 * A single backtest run touches 500 symbols × N trading days — it can run
 * for several minutes. Isolating it on a dedicated pool prevents it from
 * blocking the scheduler or HTTP threads.
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "backtestExecutor")
    public Executor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);       // one backtest at a time
        executor.setMaxPoolSize(2);        // allow a second if needed
        executor.setQueueCapacity(5);      // queue up to 5 pending requests
        executor.setThreadNamePrefix("orb-backtest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}