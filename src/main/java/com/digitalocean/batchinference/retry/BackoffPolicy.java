package com.digitalocean.batchinference.retry;

import java.time.Duration;

public interface BackoffPolicy {

    /**
     * Delay before the given attempt, where {@code attemptNumber} is 1-based and counts
     * the attempt about to be made. {@code retryAfterHint} is nullable.
     */
    Duration nextDelay(int attemptNumber, Duration retryAfterHint);
}
