package com.digitalocean.batchinference.inference;

import com.digitalocean.batchinference.config.InferenceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real client, mapper and classifier against a stand-in that answers the way
 * {@code https://inference.do-ai.run/v1/chat/completions} does.
 *
 * <p>The unit tests check each piece in isolation; this one checks that they agree on
 * the wire. It is the test that would have caught the {@code X-API-Key} header and the
 * {@code Retry-After}-only rate limit hint, since both are correct in isolation and
 * wrong only against the actual endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mock.inference.enabled=false")
@Import(DigitalOceanInferenceContractTest.FakeServerlessInference.class)
class DigitalOceanInferenceContractTest {

    private static final String API_KEY = "test-model-access-key";
    private static final String MODEL = "llama3.3-70b-instruct";

    @LocalServerPort
    private int port;

    @Autowired
    private FakeServerlessInference endpoint;

    private HttpInferenceClient client() {
        ObjectMapper objectMapper = new ObjectMapper();
        InferenceProperties properties = new InferenceProperties(
                "http://localhost:" + port + "/fake-do/v1", "/chat/completions", API_KEY, MODEL,
                256, 0.7, Duration.ofSeconds(2), Duration.ofSeconds(10), "http", "chat-completions");
        InferencePayloadMapper mapper = new ChatCompletionsPayloadMapper(objectMapper, properties);
        return new HttpInferenceClient(properties, mapper,
                new ErrorClassifier(mapper, objectMapper, Clock.systemUTC()));
    }

    @Test
    void aCompletionRoundTripsWithTheBearerHeaderAndTheOpenAiBody() {
        endpoint.reset();

        InferenceOutcome outcome = client().infer(new InferenceRequest("What is the capital of Portugal?"));

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.Success.class, success -> {
            assertThat(success.completion()).isEqualTo("Lisbon.");
            assertThat(success.tokens()).isEqualTo(20);
        });
        // The endpoint rejects anything but Bearer, so this pins the auth scheme.
        assertThat(endpoint.lastAuthorization()).isEqualTo("Bearer " + API_KEY);

        Map<String, Object> body = endpoint.lastBody();
        assertThat(body.get("model")).isEqualTo(MODEL);
        assertThat(body.get("max_completion_tokens")).isEqualTo(256);
        assertThat(body.get("stream")).isEqualTo(false);
        assertThat(body.get("messages")).isEqualTo(List.of(Map.of(
                "role", "user", "content", "What is the capital of Portugal?")));
    }

    /**
     * DigitalOcean reports throttling with {@code ratelimit-reset} — an absolute epoch
     * second — and sends no {@code Retry-After}. Reading only the latter would drop the
     * hint and leave the backoff guessing.
     */
    @Test
    void throttlingIsReadFromRatelimitResetRatherThanRetryAfter() {
        endpoint.reset();
        endpoint.throttleNextCall();

        InferenceOutcome outcome = client().infer(new InferenceRequest("anything"));

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.RateLimited.class,
                limited -> assertThat(limited.retryAfterHint())
                        .isNotNull()
                        .isBetween(Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    @Test
    void aRejectedKeyIsTerminalAndCarriesTheEndpointsExplanation() {
        endpoint.reset();
        endpoint.rejectNextKey();

        InferenceOutcome outcome = client().infer(new InferenceRequest("anything"));

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.TerminalFailure.class, terminal -> {
            assertThat(terminal.statusCode()).isEqualTo(401);
            assertThat(terminal.reason()).contains("Unable to authenticate you.");
        });
    }

    @TestConfiguration
    @RestController
    @RequestMapping("/fake-do/v1")
    static class FakeServerlessInference {

        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> body = new AtomicReference<>();
        private final AtomicInteger throttle = new AtomicInteger();
        private final AtomicInteger reject = new AtomicInteger();

        void reset() {
            authorization.set(null);
            body.set(null);
            throttle.set(0);
            reject.set(0);
        }

        void throttleNextCall() {
            throttle.set(1);
        }

        void rejectNextKey() {
            reject.set(1);
        }

        String lastAuthorization() {
            return authorization.get();
        }

        Map<String, Object> lastBody() {
            return body.get();
        }

        @PostMapping("/chat/completions")
        ResponseEntity<Map<String, Object>> chatCompletions(
                @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                @RequestBody Map<String, Object> request) {
            authorization.set(auth);
            body.set(request);

            if (reject.getAndSet(0) > 0) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("id", "unauthorized", "message", "Unable to authenticate you."));
            }
            if (throttle.getAndSet(0) > 0) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("ratelimit-limit", "250")
                        .header("ratelimit-remaining", "0")
                        .header("ratelimit-reset", Long.toString(Instant.now().plusSeconds(2).getEpochSecond()))
                        .body(Map.of("id", "too_many_requests", "message", "API rate limit exceeded."));
            }
            return ResponseEntity.ok(Map.of(
                    "id", "chatcmpl-abc123",
                    "object", "chat.completion",
                    "created", 1677649420,
                    "model", request.get("model"),
                    "choices", List.of(Map.of(
                            "index", 0,
                            "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", "Lisbon."))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)));
        }
    }
}
