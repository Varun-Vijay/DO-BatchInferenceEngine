package com.digitalocean.batchinference.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the simulated inference endpoint. Nothing reads these yet — the mock
 * controller is owned by another agent — but they are declared so the yaml binds.
 */
@ConfigurationProperties(prefix = "mock")
public record MockProperties(@DefaultValue Inference inference) {

    public record Inference(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("150ms") Duration baseLatency,
            @DefaultValue("100ms") Duration latencyJitter,
            @DefaultValue("4") int maxConcurrent,
            @DefaultValue("1s") Duration retryAfter
    ) {
    }
}
