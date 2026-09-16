package com.digitalocean.batchinference.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers how DigitalOcean serverless inference actually reports failure: a 429 carries
 * {@code ratelimit-reset} rather than {@code Retry-After}, and error bodies carry a
 * {@code message} that names the cause.
 */
class ErrorClassifierTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long LATENCY_MS = 42;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ErrorClassifier classifier =
            new ErrorClassifier(new PassthroughPayloadMapper(new ObjectMapper()), new ObjectMapper(), clock);

    private static ResponseEntity<String> response(HttpStatus status, String body, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }

    @Test
    void rateLimitResetIsConvertedFromAnAbsoluteEpochToARemainingWait() {
        ResponseEntity<String> response = response(HttpStatus.TOO_MANY_REQUESTS,
                "{\"id\":\"too_many_requests\",\"message\":\"API rate limit exceeded.\"}",
                headers("ratelimit-reset", Long.toString(NOW.plusSeconds(30).getEpochSecond())));

        InferenceOutcome outcome = classifier.classify(response, null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.RateLimited.class,
                limited -> assertThat(limited.retryAfterHint()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void retryAfterWinsOverRateLimitResetWhenBothArePresent() {
        HttpHeaders both = headers(HttpHeaders.RETRY_AFTER, "2");
        both.add("ratelimit-reset", Long.toString(NOW.plusSeconds(30).getEpochSecond()));

        InferenceOutcome outcome = classifier.classify(
                response(HttpStatus.TOO_MANY_REQUESTS, "", both), null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.RateLimited.class,
                limited -> assertThat(limited.retryAfterHint()).isEqualTo(Duration.ofSeconds(2)));
    }

    /**
     * The hourly quota can reset the better part of an hour out. The backoff treats the
     * hint as a floor and sleeps holding an in-flight permit, so an uncapped hint would
     * park a worker long enough to stall every batch behind it.
     */
    @Test
    void anHourlyQuotaResetIsCappedRatherThanParkingTheWorker() {
        ResponseEntity<String> response = response(HttpStatus.TOO_MANY_REQUESTS, "",
                headers("ratelimit-reset", Long.toString(NOW.plusSeconds(3000).getEpochSecond())));

        InferenceOutcome outcome = classifier.classify(response, null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.RateLimited.class,
                limited -> assertThat(limited.retryAfterHint()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void aResetTimestampInThePastYieldsNoHint() {
        ResponseEntity<String> response = response(HttpStatus.TOO_MANY_REQUESTS, "",
                headers("ratelimit-reset", Long.toString(NOW.minusSeconds(5).getEpochSecond())));

        InferenceOutcome outcome = classifier.classify(response, null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.RateLimited.class,
                limited -> assertThat(limited.retryAfterHint()).isNull());
    }

    @Test
    void gatewayStatusesAreTransientSoTheyGetRetried() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT}) {
            InferenceOutcome outcome =
                    classifier.classify(response(status, "", new HttpHeaders()), null, LATENCY_MS);
            assertThat(outcome)
                    .as("HTTP %d", status.value())
                    .isInstanceOf(InferenceOutcome.TransientFailure.class);
        }
    }

    @Test
    void internalServerErrorStaysTerminal() {
        InferenceOutcome outcome = classifier.classify(
                response(HttpStatus.INTERNAL_SERVER_ERROR, "{\"id\":\"server_error\",\"message\":\"Unexpected server-side error\"}",
                        new HttpHeaders()), null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.TerminalFailure.class, terminal -> {
            assertThat(terminal.statusCode()).isEqualTo(500);
            assertThat(terminal.reason()).contains("Unexpected server-side error");
        });
    }

    @Test
    void theEndpointsOwnErrorMessageSurvivesIntoTheFailureReason() {
        InferenceOutcome outcome = classifier.classify(
                response(HttpStatus.UNAUTHORIZED, "{\"id\":\"unauthorized\",\"message\":\"Unable to authenticate you.\"}",
                        new HttpHeaders()), null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.TerminalFailure.class, terminal -> {
            assertThat(terminal.statusCode()).isEqualTo(401);
            assertThat(terminal.reason()).contains("Unable to authenticate you.");
        });
    }

    @Test
    void aNonJsonErrorBodyFallsBackToTheRawText() {
        InferenceOutcome outcome = classifier.classify(
                response(HttpStatus.BAD_REQUEST, "plain text explanation", new HttpHeaders()), null, LATENCY_MS);

        assertThat(outcome).isInstanceOfSatisfying(InferenceOutcome.TerminalFailure.class,
                terminal -> assertThat(terminal.reason()).contains("plain text explanation"));
    }
}
