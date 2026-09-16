package com.digitalocean.batchinference.mock;

import com.digitalocean.batchinference.config.MockProperties;
import com.digitalocean.batchinference.inference.InferenceRequest;
import com.digitalocean.batchinference.inference.InferenceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for the real inference endpoint. Throttling is driven by actual in-flight
 * concurrency rather than an every-Nth-request counter, so a client that backs off
 * correctly sees its 429 rate drop; a fixed pattern would not show that.
 */
@RestController
@RequestMapping("/mock/v1")
@ConditionalOnProperty(name = "mock.inference.enabled", havingValue = "true")
public class MockInferenceController {

    private static final Logger log = LoggerFactory.getLogger(MockInferenceController.class);

    private static final int ECHO_CHAR_LIMIT = 80;
    private static final int CHARS_PER_TOKEN = 4;

    private final AtomicInteger inFlight = new AtomicInteger();

    private final int maxConcurrent;
    private final long baseLatencyMillis;
    private final long latencyJitterMillis;
    private final String retryAfterSeconds;

    public MockInferenceController(MockProperties properties) {
        MockProperties.Inference inference = properties.inference();
        this.maxConcurrent = inference.maxConcurrent();
        this.baseLatencyMillis = inference.baseLatency().toMillis();
        this.latencyJitterMillis = inference.latencyJitter().toMillis();
        // Retry-After must be delta-seconds, never an HTTP-date. Round a sub-second
        // configured value up to 1 so a client is never told to retry immediately.
        this.retryAfterSeconds = Long.toString(Math.max(1L, inference.retryAfter().toSeconds()));
    }

    @PostMapping("/infer")
    public ResponseEntity<InferenceResponse> infer(@RequestBody InferenceRequest request) {
        int current = inFlight.incrementAndGet();
        try {
            if (current > maxConcurrent) {
                log.warn("Throttling /mock/v1/infer: in-flight={} exceeds max-concurrent={}", current, maxConcurrent);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                        .build();
            }
            simulateLatency();
            return ResponseEntity.ok(complete(request));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void simulateLatency() {
        long millis = baseLatencyMillis;
        if (latencyJitterMillis > 0) {
            millis += ThreadLocalRandom.current().nextLong(latencyJitterMillis);
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static InferenceResponse complete(InferenceRequest request) {
        String prompt = request == null || request.prompt() == null ? "" : request.prompt();
        String echo = prompt.length() <= ECHO_CHAR_LIMIT ? prompt : prompt.substring(0, ECHO_CHAR_LIMIT) + "...";
        String completion = "mock completion for: " + echo;
        int tokens = Math.max(1, (prompt.length() + completion.length()) / CHARS_PER_TOKEN);
        return new InferenceResponse(completion, tokens);
    }
}
