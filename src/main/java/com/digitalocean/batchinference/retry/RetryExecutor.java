package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.inference.InferenceOutcome;

import java.util.function.Supplier;

/**
 * Wraps an inference call with a retry policy. Implementations decide which outcomes
 * are worth another attempt and how long to wait in between.
 */
public interface RetryExecutor {

    RetryResult execute(Supplier<InferenceOutcome> call);

    record RetryResult(InferenceOutcome finalOutcome, int attempts, long totalWaitMs) {
    }
}
