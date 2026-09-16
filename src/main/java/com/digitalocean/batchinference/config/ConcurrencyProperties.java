package com.digitalocean.batchinference.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "concurrency")
public record ConcurrencyProperties(
        /** Global cap on simultaneous in-flight inference calls, across all batches. */
        @Min(1) @DefaultValue("10") int maxInFlight,
        /** Depth of the pending-batch queue; submissions beyond it are rejected with 503. */
        @Min(1) @DefaultValue("100") int batchQueueCapacity,
        @Min(1) @DefaultValue("1000") int maxBatchSize
) {
}
