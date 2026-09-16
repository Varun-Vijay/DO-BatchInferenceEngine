package com.digitalocean.batchinference.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "inference")
public record InferenceProperties(
        @DefaultValue("https://inference.do-ai.run/v1") String baseUrl,
        /** Path appended to {@code baseUrl}; the DigitalOcean chat endpoint by default. */
        @DefaultValue("/chat/completions") String path,
        /**
         * DigitalOcean model access key, sent as {@code Authorization: Bearer}. A
         * personal access token works too — the endpoint accepts either.
         */
        @DefaultValue("") String apiKey,
        /** Model id to prompt; {@code GET /v1/models} lists what the key can reach. */
        @DefaultValue("llama3.3-70b-instruct") String model,
        /** Cap on generated tokens per prompt. Sent as {@code max_completion_tokens}. */
        @DefaultValue("512") int maxCompletionTokens,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("2s") Duration connectTimeout,
        /** Generation is slow: this bounds a whole completion, not a mock round trip. */
        @DefaultValue("60s") Duration readTimeout,
        /**
         * Which {@code InferenceClient} to register: {@code http} for the real endpoint,
         * {@code stub} for the echoing placeholder. Read by {@code @ConditionalOnProperty}
         * before binding happens, so nothing injects this — it is declared to keep the
         * switch discoverable in configuration metadata rather than implicit in annotations.
         */
        @DefaultValue("http") String client,
        /**
         * Wire format for the request and response body: {@code chat-completions} for the
         * DigitalOcean/OpenAI shape, {@code passthrough} for the neutral
         * {@code {"prompt": ...}} shape the in-app mock speaks. Read by
         * {@code @ConditionalOnProperty} before binding, like {@link #client()}.
         */
        @DefaultValue("chat-completions") String payloadFormat
) {
}
