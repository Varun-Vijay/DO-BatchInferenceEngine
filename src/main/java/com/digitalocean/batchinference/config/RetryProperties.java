package com.digitalocean.batchinference.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "retry")
public record RetryProperties(
        /** Total attempts including the initial call, so 3 means one call plus two retries. */
        @Min(1) @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration initialBackoff,
        @DefaultValue("5s") Duration maxBackoff,
        /**
         * Which {@code RetryExecutor} to register: {@code default} to retry with backoff,
         * {@code none} to call once and report verbatim when diagnosing the scheduling
         * core. Read by {@code @ConditionalOnProperty} before binding, so nothing injects
         * it; see {@link InferenceProperties#client()} for the same pattern.
         */
        @DefaultValue("default") String executor
) {
}
