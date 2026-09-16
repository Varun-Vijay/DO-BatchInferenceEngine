package com.digitalocean.batchinference.inference;

import java.time.Duration;

/**
 * Result of a single inference attempt. Expected failures are values, not exceptions,
 * so the retry layer can classify them by pattern matching instead of by catch blocks.
 */
public sealed interface InferenceOutcome {

    record Success(String completion, int tokens, long latencyMs) implements InferenceOutcome {
    }

    /** {@code retryAfterHint} is nullable: the endpoint does not always supply one. */
    record RateLimited(Duration retryAfterHint) implements InferenceOutcome {
    }

    record TransientFailure(String reason) implements InferenceOutcome {
    }

    record TerminalFailure(int statusCode, String reason) implements InferenceOutcome {
    }
}
