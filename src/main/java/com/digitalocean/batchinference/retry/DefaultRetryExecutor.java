package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.config.RetryProperties;
import com.digitalocean.batchinference.inference.InferenceOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Retries a call while the outcome is worth retrying, sleeping for a
 * {@link BackoffPolicy} delay in between.
 *
 * <p>Only {@code RateLimited} and {@code TransientFailure} are retried. A
 * {@code TerminalFailure} is returned as-is: a second identical request cannot succeed,
 * so retrying it only burns the attempt budget and holds an in-flight permit that the
 * rest of the batch is waiting for.
 *
 * <p>{@code maxAttempts} is the total number of calls, not the number of retries, so a
 * configured 3 means one initial call and at most two more.
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "retry", name = "executor", havingValue = "default", matchIfMissing = true)
public class DefaultRetryExecutor implements RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetryExecutor.class);

    private final BackoffPolicy backoffPolicy;
    private final int maxAttempts;

    public DefaultRetryExecutor(BackoffPolicy backoffPolicy, RetryProperties properties) {
        this.backoffPolicy = backoffPolicy;
        this.maxAttempts = Math.max(1, properties.maxAttempts());
    }

    @Override
    public RetryResult execute(Supplier<InferenceOutcome> call) {
        long totalWaitMs = 0L;
        InferenceOutcome outcome = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            outcome = call.get();

            if (!isRetryable(outcome)) {
                log.debug("Attempt {}/{} returned {} — not retryable",
                        attempt, maxAttempts, outcome.getClass().getSimpleName());
                return new RetryResult(outcome, attempt, totalWaitMs);
            }
            if (attempt == maxAttempts) {
                log.debug("Attempt {}/{} returned {} — attempt budget exhausted",
                        attempt, maxAttempts, outcome.getClass().getSimpleName());
                break;
            }

            Duration delay = backoffPolicy.nextDelay(attempt + 1, retryAfterHint(outcome));
            log.debug("Attempt {}/{} returned {} — retrying in {}ms",
                    attempt, maxAttempts, outcome.getClass().getSimpleName(), delay.toMillis());
            try {
                // Blocking is cheap here: callers run on virtual threads, so the carrier
                // thread is released for the duration of the sleep.
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Interrupted while backing off after attempt {}/{} — giving up",
                        attempt, maxAttempts);
                return new RetryResult(outcome, attempt, totalWaitMs);
            }
            totalWaitMs += delay.toMillis();
        }

        return new RetryResult(outcome, maxAttempts, totalWaitMs);
    }

    private static boolean isRetryable(InferenceOutcome outcome) {
        return switch (outcome) {
            case InferenceOutcome.RateLimited ignored -> true;
            case InferenceOutcome.TransientFailure ignored -> true;
            case InferenceOutcome.Success ignored -> false;
            case InferenceOutcome.TerminalFailure ignored -> false;
        };
    }

    private static Duration retryAfterHint(InferenceOutcome outcome) {
        return outcome instanceof InferenceOutcome.RateLimited rateLimited
                ? rateLimited.retryAfterHint()
                : null;
    }
}
