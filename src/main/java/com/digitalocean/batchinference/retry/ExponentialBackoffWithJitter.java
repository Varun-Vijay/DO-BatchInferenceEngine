package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.config.RetryProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Full-jitter exponential backoff: the exponential term is a ceiling, and the actual
 * delay is drawn uniformly from {@code [0, cap)}.
 *
 * <p>Plain exponential backoff is the wrong tool here. Ten workers that take a 429 at
 * the same instant compute the same delay, sleep in lockstep, and rebuild the original
 * burst one backoff later. Randomising the whole interval spreads their retries across
 * the window, which is the only reason this class exists.
 *
 * <p>A {@code Retry-After} hint is a floor, never a ceiling: the endpoint knows when it
 * expects to be ready, so the jitter is added on top of the hint rather than replacing
 * it. Taking {@code max(hint, jitter)} instead would collapse the spread to exactly the
 * hint whenever the hint outruns the jitter window — which is the common case, since a
 * hint only ever accompanies a 429 and the window starts at a fraction of a second. That
 * is precisely the burst this class exists to break up, so the delay must stay random
 * even when the floor dominates.
 */
@Component
public class ExponentialBackoffWithJitter implements BackoffPolicy {

    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public ExponentialBackoffWithJitter(RetryProperties properties) {
        this.initialBackoffMs = properties.initialBackoff().toMillis();
        this.maxBackoffMs = properties.maxBackoff().toMillis();
    }

    @Override
    public Duration nextDelay(int attemptNumber, Duration retryAfterHint) {
        long cap = capMillis(attemptNumber);
        long jittered = cap <= 0 ? 0L : ThreadLocalRandom.current().nextLong(0, cap);
        long hint = retryAfterHint == null ? 0L : Math.max(0L, retryAfterHint.toMillis());
        return Duration.ofMillis(saturatingSum(hint, jittered));
    }

    /** A hostile {@code Retry-After} can sit near {@code Long.MAX_VALUE}; clamp, don't wrap. */
    private static long saturatingSum(long hint, long jittered) {
        try {
            return Math.addExact(hint, jittered);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * {@code min(maxBackoff, initialBackoff * 2^attemptNumber)}, saturating instead of
     * overflowing — a long-running task can reach an attempt number that shifts past 63.
     */
    private long capMillis(int attemptNumber) {
        if (attemptNumber >= Long.SIZE - 1) {
            return maxBackoffMs;
        }
        long scaled;
        try {
            scaled = Math.multiplyExact(initialBackoffMs, 1L << attemptNumber);
        } catch (ArithmeticException overflow) {
            return maxBackoffMs;
        }
        return Math.min(maxBackoffMs, scaled);
    }
}
