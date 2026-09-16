package com.digitalocean.batchinference.inference;

import com.digitalocean.batchinference.config.InferenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Calls {@code POST {inference.base-url}{inference.path}} and reports the result as a
 * value. Against DigitalOcean serverless inference that is
 * {@code https://inference.do-ai.run/v1/chat/completions}.
 *
 * <p>{@link #infer} never throws: every transport error, error status and parse failure
 * is turned into an {@link InferenceOutcome} by {@link ErrorClassifier}, so the
 * scheduling layer only ever pattern matches.
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "inference", name = "client", havingValue = "http", matchIfMissing = true)
public class HttpInferenceClient implements InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpInferenceClient.class);

    private final RestClient restClient;
    private final String path;
    private final InferencePayloadMapper payloadMapper;
    private final ErrorClassifier errorClassifier;

    public HttpInferenceClient(InferenceProperties properties,
                               InferencePayloadMapper payloadMapper,
                               ErrorClassifier errorClassifier) {
        this.payloadMapper = payloadMapper;
        this.errorClassifier = errorClassifier;
        this.path = properties.path();
        this.restClient = buildRestClient(properties);
        log.info("HTTP inference client ready: url={}{} model={} connectTimeout={} readTimeout={} apiKey={}",
                properties.baseUrl(), properties.path(), properties.model(),
                properties.connectTimeout(), properties.readTimeout(),
                StringUtils.hasText(properties.apiKey()) ? "set" : "absent");
    }

    /**
     * Both timeouts have to be wired into the request factory by hand — the properties
     * on their own are inert, and a RestClient built without them waits forever.
     */
    private static RestClient buildRestClient(InferenceProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                // Error statuses are data here, not exceptions: the classifier decides.
                .defaultStatusHandler(status -> true, (request, response) -> {
                });
        if (StringUtils.hasText(properties.apiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return builder.build();
    }

    @Override
    public InferenceOutcome infer(InferenceRequest request) {
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = null;
        Throwable failure = null;
        try {
            response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payloadMapper.toWirePayload(request))
                    .retrieve()
                    .toEntity(String.class);
        } catch (Exception e) {a
            failure = e;
        }
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (failure != null && hasInterrupt(failure)) {
            Thread.currentThread().interrupt();
        }

        InferenceOutcome outcome;
        try {
            outcome = errorClassifier.classify(response, failure, latencyMs);
        } catch (RuntimeException e) {
            // The classifier is not supposed to fail, but infer() promises an outcome.
            outcome = new InferenceOutcome.TransientFailure("classification failed: " + e);
        }
        log.debug("inference call status={} outcome={} latencyMs={}",
                response == null ? "none" : response.getStatusCode().value(),
                outcome.getClass().getSimpleName(), latencyMs);
        return outcome;
    }

    private boolean hasInterrupt(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
