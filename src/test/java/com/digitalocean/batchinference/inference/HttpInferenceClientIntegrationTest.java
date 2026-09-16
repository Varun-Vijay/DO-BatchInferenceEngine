package com.digitalocean.batchinference.inference;

import com.digitalocean.batchinference.config.InferenceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real client against the mock endpoint. The client is built by hand rather
 * than autowired because the base URL has to name the random port the test server
 * actually bound to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mock.inference.enabled=true")
class HttpInferenceClientIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpInferenceClientIntegrationTest.class);
    private static final int CONCURRENT_CALLS = 20;

    @LocalServerPort
    private int port;

    private static HttpInferenceClient clientFor(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        InferencePayloadMapper mapper = new PassthroughPayloadMapper(new ObjectMapper());
        return new HttpInferenceClient(mockProperties(baseUrl, connectTimeout, readTimeout),
                mapper, classifierFor(mapper));
    }

    /** The mock speaks the passthrough shape at {@code /infer}, not the DigitalOcean one. */
    private static InferenceProperties mockProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        return new InferenceProperties(baseUrl, "/infer", "", "unused-model", 512, 0.7,
                connectTimeout, readTimeout, "http", "passthrough");
    }

    private static ErrorClassifier classifierFor(InferencePayloadMapper mapper) {
        return new ErrorClassifier(mapper, new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void twentyConcurrentCallsYieldBothSuccessAndRateLimitedWithAHint() throws Exception {
        HttpInferenceClient client =
                clientFor("http://localhost:" + port + "/mock/v1", Duration.ofSeconds(2), Duration.ofSeconds(10));

        List<InferenceOutcome> outcomes;
        CountDownLatch startGun = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLS)) {
            List<Future<InferenceOutcome>> futures = IntStream.range(0, CONCURRENT_CALLS)
                    .mapToObj(i -> pool.submit(() -> {
                        startGun.await();
                        return client.infer(new InferenceRequest("concurrent prompt " + i));
                    }))
                    .toList();
            startGun.countDown();
            outcomes = futures.stream().map(HttpInferenceClientIntegrationTest::get).toList();
        }

        Map<String, Long> byType = outcomes.stream().collect(Collectors.groupingBy(
                outcome -> outcome.getClass().getSimpleName(), Collectors.counting()));
        log.info("ACCEPTANCE 2: {} concurrent calls -> {}", CONCURRENT_CALLS, byType);

        assertThat(outcomes).hasSize(CONCURRENT_CALLS);
        assertThat(outcomes).hasAtLeastOneElementOfType(InferenceOutcome.Success.class);
        assertThat(outcomes).hasAtLeastOneElementOfType(InferenceOutcome.RateLimited.class);
        assertThat(outcomes).filteredOn(InferenceOutcome.RateLimited.class::isInstance)
                .allSatisfy(outcome -> {
                    Duration hint = ((InferenceOutcome.RateLimited) outcome).retryAfterHint();
                    log.info("ACCEPTANCE 2: RateLimited retryAfterHint={}", hint);
                    assertThat(hint).isNotNull().isEqualTo(Duration.ofSeconds(1));
                });
        assertThat(outcomes).filteredOn(InferenceOutcome.Success.class::isInstance)
                .allSatisfy(outcome -> assertThat(((InferenceOutcome.Success) outcome).completion()).isNotBlank());
    }

    @Test
    void deadPortReturnsTransientFailureWithoutThrowingOrHanging() throws Exception {
        HttpInferenceClient client = clientFor("http://localhost:" + unusedPort() + "/mock/v1",
                Duration.ofSeconds(2), Duration.ofSeconds(10));

        long startNanos = System.nanoTime();
        InferenceOutcome outcome = client.infer(new InferenceRequest("nobody is listening"));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("ACCEPTANCE 3: dead port -> {} in {}ms", outcome, elapsedMs);
        assertThat(outcome).isInstanceOf(InferenceOutcome.TransientFailure.class);
        assertThat(elapsedMs).isLessThan(Duration.ofSeconds(2).toMillis());
    }

    @Test
    void readTimeoutIsAppliedAndClassifiedAsTransient() {
        // The mock sleeps at least 150ms, so a 50ms read timeout always trips.
        HttpInferenceClient client = clientFor("http://localhost:" + port + "/mock/v1",
                Duration.ofSeconds(2), Duration.ofMillis(50));

        long startNanos = System.nanoTime();
        InferenceOutcome outcome = client.infer(new InferenceRequest("slower than the read timeout"));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("ACCEPTANCE 3: read timeout -> {} in {}ms", outcome, elapsedMs);
        assertThat(outcome).isInstanceOf(InferenceOutcome.TransientFailure.class);
        assertThat(elapsedMs).isLessThan(Duration.ofSeconds(2).toMillis());
    }

    @Test
    void errorStatusIsTerminalAndNeverReachesThePayloadMapper() {
        InferencePayloadMapper exploding = new InferencePayloadMapper() {
            @Override
            public Object toWirePayload(InferenceRequest request) {
                return request;
            }

            @Override
            public InferenceResponse fromWirePayload(String responseBody) {
                throw new AssertionError("payload mapper must not run on a non-2xx response");
            }
        };
        InferenceProperties properties = mockProperties("http://localhost:" + port + "/mock/no-such-api",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
        HttpInferenceClient client =
                new HttpInferenceClient(properties, exploding, classifierFor(exploding));

        InferenceOutcome outcome = client.infer(new InferenceRequest("unknown route"));

        log.info("ACCEPTANCE 4: unknown route -> {}", outcome);
        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.TerminalFailure.class,
                terminal -> assertThat(terminal.statusCode()).isEqualTo(404));
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
