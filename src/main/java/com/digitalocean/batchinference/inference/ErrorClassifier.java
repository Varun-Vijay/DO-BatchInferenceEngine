package com.digitalocean.batchinference.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The single place where a transport result becomes an {@link InferenceOutcome}.
 *
 * <p>The whole retry policy is the branch table in {@link #classify}: reclassifying a
 * status — say, making 500 transient — is a one-line change there and nowhere else.
 */
@Component
public class ErrorClassifier {

    /** No HTTP exchange completed, so there is no status to report. */
    private static final int NO_STATUS = 0;

    /** DigitalOcean's throttling header: Unix epoch seconds when the quota frees up. */
    private static final String RATELIMIT_RESET_HEADER = "ratelimit-reset";

    /**
     * Ceiling on any hint taken from a response. The backoff policy treats a hint as a
     * floor and sleeps for it while holding an in-flight permit, so an hourly-quota
     * {@code ratelimit-reset} pointing 50 minutes out would park a worker for 50
     * minutes and stall the batch behind it. Waiting less than asked risks another 429,
     * which the attempt budget already handles.
     */
    private static final Duration MAX_RETRY_HINT = Duration.ofSeconds(60);

    /** Error bodies are {@code {"id": ..., "message": ...}}; the message names the cause. */
    private static final String ERROR_MESSAGE_FIELD = "message";
    private static final int MAX_REASON_LENGTH = 300;

    private final InferencePayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ErrorClassifier(InferencePayloadMapper payloadMapper, ObjectMapper objectMapper, Clock clock) {
        this.payloadMapper = payloadMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Exactly one of {@code response} and {@code failure} is expected to be non-null.
     *
     * <p>Status is inspected before anything else and the payload mapper runs only on
     * 2xx: a 429 arrives with an error body, so an unconditional mapper call would fail
     * on the one path that most needs to produce a clean outcome.
     */
    public InferenceOutcome classify(ResponseEntity<String> response, Throwable failure, long latencyMs) {
        if (response == null) {
            return causedByIo(failure)
                    ? new InferenceOutcome.TransientFailure(describe(failure))
                    : new InferenceOutcome.TerminalFailure(NO_STATUS, describe(failure));
        }

        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            try {
                InferenceResponse body = payloadMapper.fromWirePayload(response.getBody());
                return new InferenceOutcome.Success(body.completion(), body.tokens(), latencyMs);
            } catch (RuntimeException e) {
                return new InferenceOutcome.TerminalFailure(status.value(), "unreadable success body: " + describe(e));
            }
        }
        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new InferenceOutcome.RateLimited(retryHint(response.getHeaders()));
        }
        // A shared serverless endpoint answers a momentary capacity or gateway problem
        // with one of these, and the next attempt commonly lands on a healthy instance.
        // A 500 stays terminal: that is the endpoint itself failing on this request.
        if (status.value() == HttpStatus.BAD_GATEWAY.value()
                || status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return new InferenceOutcome.TransientFailure("HTTP " + status.value() + describedBody(response));
        }
        // Everything else is terminal — a bad model id, a rejected key or a malformed
        // body cannot be fixed by sending the identical request again.
        return new InferenceOutcome.TerminalFailure(status.value(), "HTTP " + status.value() + describedBody(response));
    }

    /**
     * Prefers {@code Retry-After} and falls back to {@code ratelimit-reset}, which is
     * what DigitalOcean actually sends on a 429. Null when neither is usable.
     */
    private Duration retryHint(HttpHeaders headers) {
        Duration retryAfter = retryAfterHint(headers);
        return retryAfter != null ? retryAfter : rateLimitResetHint(headers);
    }

    /** {@code Retry-After: <integer seconds>}; null when absent or not an integer. */
    private Duration retryAfterHint(HttpHeaders headers) {
        Long seconds = longHeader(headers, HttpHeaders.RETRY_AFTER);
        return seconds == null ? null : clampHint(Duration.ofSeconds(seconds));
    }

    /**
     * {@code ratelimit-reset} is an absolute Unix epoch second, not a delay, so the wait
     * is the distance from now. A timestamp already in the past means the quota has
     * freed up and there is nothing to wait for.
     */
    private Duration rateLimitResetHint(HttpHeaders headers) {
        Long epochSeconds = longHeader(headers, RATELIMIT_RESET_HEADER);
        if (epochSeconds == null) {
            return null;
        }
        Duration until = Duration.between(Instant.now(clock), Instant.ofEpochSecond(epochSeconds));
        return until.isNegative() || until.isZero() ? null : clampHint(until);
    }

    private static Duration clampHint(Duration hint) {
        if (hint.isNegative()) {
            return null;
        }
        return hint.compareTo(MAX_RETRY_HINT) > 0 ? MAX_RETRY_HINT : hint;
    }

    private static Long longHeader(HttpHeaders headers, String name) {
        String header = headers.getFirst(name);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Appends the endpoint's own explanation when it sent one. Without it every failure
     * reads as a bare status code, and "HTTP 404" gives no hint that the real problem is
     * an unknown model id.
     */
    private String describedBody(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return "";
        }
        String message = body.trim();
        try {
            JsonNode node = objectMapper.readTree(body).get(ERROR_MESSAGE_FIELD);
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                message = node.asText().trim();
            }
        } catch (IOException ignored) {
            // Not JSON, or not the documented error shape: fall back to the raw body.
        }
        return ": " + truncate(message);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH) + "...";
    }

    /**
     * Connect and read timeouts, connection resets and broken pipes all surface as an
     * {@link IOException} somewhere in the cause chain, below whatever the HTTP client
     * wrapped them in.
     */
    private boolean causedByIo(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : root.getClass().getSimpleName() + ": " + message;
    }
}
