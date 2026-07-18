package com.vasundhara.atf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Test runs are long-lived; they execute on a dedicated bounded pool so the web
 * tier stays responsive while runs are in flight. {@link AtfProperties} is bound via
 * its own {@code @Component} + {@code @ConfigurationProperties} annotations.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "testRunExecutor")
    public Executor testRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Multi-device execution runs one independent session PER selected device, simultaneously.
        // Core pool is sized so a typical fleet of devices/emulators all execute in parallel rather
        // than queueing (a ThreadPoolTaskExecutor only grows past the core size once the queue is
        // full). Each task targets a distinct device, so concurrent runs do not contend.
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("atf-run-");
        executor.initialize();
        return executor;
    }
}
