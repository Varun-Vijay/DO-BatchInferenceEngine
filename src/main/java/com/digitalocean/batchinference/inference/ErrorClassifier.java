package com.digitalocean.batchinference.inference;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * The single place where a transport result becomes an {@link InferenceOutcome}.
 *
 * <p>The whole retry policy is the branch table in {@link #classify}: reclassifying a
 * status — say, making 503 transient — is a one-line change there and nowhere else.
 */
@Component
public class ErrorClassifier {

    /** No HTTP exchange completed, so there is no status to report. */
    private static final int NO_STATUS = 0;

    private final InferencePayloadMapper payloadMapper;

    public ErrorClassifier(InferencePayloadMapper payloadMapper) {
        this.payloadMapper = payloadMapper;
    }

    /**
     * Exactly one of {@code response} and {@code failure} is expected to be non-null.
     *
     * <p>Status is inspected before anything else and the payload mapper runs only on
     * 2xx: a 429 arrives with an empty body, so an unconditional mapper call would fail
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
            return new InferenceOutcome.RateLimited(retryAfterHint(response.getHeaders()));
        }
        // Everything else is terminal, 5xx included: a server that is failing is not
        // something this client should hammer. Move a status above this line to retry it.
        return new InferenceOutcome.TerminalFailure(status.value(), "HTTP " + status.value());
    }

    /** {@code Retry-After: <integer seconds>}; null when absent or not an integer. */
    private Duration retryAfterHint(HttpHeaders headers) {
        String header = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
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
