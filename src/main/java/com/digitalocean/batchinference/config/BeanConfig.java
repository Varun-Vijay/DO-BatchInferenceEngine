package com.digitalocean.batchinference.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Configuration
@EnableConfigurationProperties({
        InferenceProperties.class,
        ConcurrencyProperties.class,
        RetryProperties.class,
        MockProperties.class
})
public class BeanConfig {

    /**
     * One long-lived virtual-thread executor for all task work. Shutdown is driven by
     * the dispatcher's {@code SmartLifecycle}, so Spring must not close it early.
     */
    @Bean(destroyMethod = "")
    public ExecutorService taskExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Global concurrency budget for calls to the inference endpoint. Virtual threads are
     * cheap, so this — not the thread pool size — is what protects the downstream.
     */
    @Bean
    public Semaphore inFlightSemaphore(ConcurrencyProperties properties) {
        return new Semaphore(properties.maxInFlight());
    }

    /**
     * Injected rather than read statically so that the {@code ratelimit-reset} arithmetic
     * in {@code ErrorClassifier} can be driven from a fixed instant in tests.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
