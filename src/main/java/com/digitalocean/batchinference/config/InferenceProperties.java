package com.digitalocean.batchinference.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(
        @DefaultValue("http://localhost:8080/mock/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        /**
         * Which {@code InferenceClient} to register: {@code http} for the real endpoint,
         * {@code stub} for the echoing placeholder. Read by {@code @ConditionalOnProperty}
         * before binding happens, so nothing injects this — it is declared to keep the
         * switch discoverable in configuration metadata rather than implicit in annotations.
         */
        @DefaultValue("http") String client
) {
}
