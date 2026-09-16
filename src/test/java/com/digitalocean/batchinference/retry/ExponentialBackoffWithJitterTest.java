package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.config.RetryProperties;
import com.digitalocean.batchinference.inference.InferenceOutcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backoff and retry are the one thing in this project that cannot be checked by eye in a
 * demo: jitter makes every run look different, and an off-by-one in the attempt budget
 * hides behind a call that eventually succeeds anyway. Hence the only unit tests here.
 *
 * <p>No Spring context, and the executor tests use a zero-or-tiny delay policy, so the
 * whole class runs in milliseconds.
 */
class ExponentialBackoffWithJitterTest {

    private static final int SAMPLES = 2_000;

    private static RetryProperties properties(int maxAttempts, Duration initial, Duration max) {
        return new RetryProperties(maxAttempts, initial, max);
    }

    @Nested
    class Jitter {

        private final ExponentialBackoffWithJitter policy = new ExponentialBackoffWithJitter(
                properties(3, Duration.ofMillis(200), Duration.ofSeconds(5)));

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void delayStaysWithinTheCapForEveryAttempt(int attemptNumber) {
            long cap = Math.min(5_000L, 200L * (1L << attemptNumber));

            for (int i = 0; i < SAMPLES; i++) {
                Duration delay = policy.nextDelay(attemptNumber, null);
                assertThat(delay.toMillis()).isBetween(0L, cap);
            }
        }

        @Test
        void delayIsActuallyRandomisedRatherThanFixed() {
            List<Long> delays = sample(policy, 4, null);

            // The point of the class: two workers backing off together must not agree.
            assertThat(Set.copyOf(delays)).hasSizeGreaterThan(SAMPLES / 4);
        }

        @ParameterizedTest
        @ValueSource(ints = {10, 31, 62, 63, 64, 1_000, Integer.MAX_VALUE})
        void capIsHonouredAtHighAttemptNumbersWithoutOverflowing(int attemptNumber) {
            for (int i = 0; i < SAMPLES; i++) {
                Duration delay = policy.nextDelay(attemptNumber, null);
                assertThat(delay).isBetween(Duration.ZERO, Duration.ofSeconds(5));
            }
        }

        @Test
        void zeroInitialBackoffDoesNotBlowUp() {
            ExponentialBackoffWithJitter noBackoff = new ExponentialBackoffWithJitter(
                    properties(3, Duration.ZERO, Duration.ofSeconds(5)));

            assertThat(noBackoff.nextDelay(2, null)).isZero();
        }
    }

    @Nested
    class RetryAfterHint {

        private final ExponentialBackoffWithJitter policy = new ExponentialBackoffWithJitter(
                properties(3, Duration.ofMillis(200), Duration.ofSeconds(5)));

        @Test
        void hintLargerThanTheJitteredDelayWins() {
            Duration hint = Duration.ofSeconds(30);

            for (int i = 0; i < SAMPLES; i++) {
                assertThat(policy.nextDelay(3, hint)).isEqualTo(hint);
            }
        }

        @Test
        void hintSmallerThanTheJitteredDelayDoesNotShortenIt() {
            Duration hint = Duration.ofMillis(1);
            long cap = Math.min(5_000L, 200L * (1L << 5));

            List<Long> delays = sample(policy, 5, hint);

            // A floor of 1ms must not collapse the distribution onto 1ms: the delays stay
            // spread across the jitter window, every sample respecting the floor.
            assertThat(delays).allSatisfy(delay -> assertThat(delay).isBetween(1L, cap));
            assertThat(Set.copyOf(delays)).hasSizeGreaterThan(SAMPLES / 4);
            assertThat(delays.stream().mapToLong(Long::longValue).max().orElseThrow())
                    .isGreaterThan(cap / 2);
        }
    }

    @Nested
    class Attempts {

        private static final Duration WAIT_PER_RETRY = Duration.ofMillis(5);

        /** Fixed delay so the attempt accounting is exact and the test stays fast. */
        private final BackoffPolicy fixedDelay = (attemptNumber, hint) -> WAIT_PER_RETRY;

        private DefaultRetryExecutor executor(int maxAttempts) {
            return new DefaultRetryExecutor(fixedDelay,
                    properties(maxAttempts, Duration.ofMillis(200), Duration.ofSeconds(5)));
        }

        @Test
        void persistentlyRateLimitedSupplierIsCalledExactlyMaxAttemptsTimes() {
            CountingSupplier supplier =
                    new CountingSupplier(new InferenceOutcome.RateLimited(null));

            RetryExecutor.RetryResult result = executor(3).execute(supplier);

            assertThat(supplier.calls()).isEqualTo(3);
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.finalOutcome()).isInstanceOf(InferenceOutcome.RateLimited.class);
            // Two waits for three attempts — the last failure is not slept on.
            assertThat(result.totalWaitMs()).isEqualTo(2 * WAIT_PER_RETRY.toMillis());
        }

        @Test
        void persistentlyFailingSupplierRespectsASingleAttemptBudget() {
            CountingSupplier supplier =
                    new CountingSupplier(new InferenceOutcome.TransientFailure("boom"));

            RetryExecutor.RetryResult result = executor(1).execute(supplier);

            assertThat(supplier.calls()).isEqualTo(1);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.totalWaitMs()).isZero();
        }

        @Test
        void terminalFailureSupplierIsCalledExactlyOnce() {
            CountingSupplier supplier =
                    new CountingSupplier(new InferenceOutcome.TerminalFailure(400, "bad prompt"));

            RetryExecutor.RetryResult result = executor(3).execute(supplier);

            assertThat(supplier.calls()).isEqualTo(1);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.finalOutcome()).isInstanceOf(InferenceOutcome.TerminalFailure.class);
            assertThat(result.totalWaitMs()).isZero();
        }

        @Test
        void successfulSupplierIsCalledExactlyOnce() {
            CountingSupplier supplier =
                    new CountingSupplier(new InferenceOutcome.Success("done", 7, 12L));

            RetryExecutor.RetryResult result = executor(3).execute(supplier);

            assertThat(supplier.calls()).isEqualTo(1);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.finalOutcome()).isInstanceOf(InferenceOutcome.Success.class);
            assertThat(result.totalWaitMs()).isZero();
        }

        @Test
        void transientFailureFollowedBySuccessStopsAtTheSuccess() {
            CountingSupplier supplier = new CountingSupplier(
                    new InferenceOutcome.TransientFailure("connection reset"),
                    new InferenceOutcome.Success("done", 7, 12L),
                    new InferenceOutcome.Success("never reached", 0, 0L));

            RetryExecutor.RetryResult result = executor(3).execute(supplier);

            assertThat(supplier.calls()).isEqualTo(2);
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.finalOutcome()).isEqualTo(new InferenceOutcome.Success("done", 7, 12L));
            assertThat(result.totalWaitMs()).isEqualTo(WAIT_PER_RETRY.toMillis());
        }

        @Test
        void interruptionDuringBackoffReturnsImmediatelyAndKeepsTheFlagSet() throws Exception {
            BackoffPolicy slow = (attemptNumber, hint) -> Duration.ofMinutes(5);
            DefaultRetryExecutor executor = new DefaultRetryExecutor(slow,
                    properties(3, Duration.ofMillis(200), Duration.ofSeconds(5)));
            CountingSupplier supplier =
                    new CountingSupplier(new InferenceOutcome.TransientFailure("boom"));

            AtomicReference<RetryExecutor.RetryResult> result = new AtomicReference<>();
            AtomicBoolean flagStillSet = new AtomicBoolean();
            Thread caller = Thread.ofVirtual().unstarted(() -> {
                result.set(executor.execute(supplier));
                flagStillSet.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            // Let the first attempt land and the thread settle into its backoff sleep.
            Thread.sleep(50);
            caller.interrupt();
            caller.join(Duration.ofSeconds(5));

            assertThat(caller.isAlive()).isFalse();
            assertThat(supplier.calls()).isEqualTo(1);
            assertThat(result.get().attempts()).isEqualTo(1);
            assertThat(result.get().totalWaitMs()).isZero();
            assertThat(flagStillSet).isTrue();
        }
    }

    private static List<Long> sample(BackoffPolicy policy, int attemptNumber, Duration hint) {
        List<Long> delays = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            delays.add(policy.nextDelay(attemptNumber, hint).toMillis());
        }
        return delays;
    }

    /** Returns each outcome in turn, repeating the last one once the list runs out. */
    private static final class CountingSupplier implements Supplier<InferenceOutcome> {

        private final List<InferenceOutcome> outcomes;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingSupplier(InferenceOutcome... outcomes) {
            this.outcomes = List.of(outcomes);
        }

        @Override
        public InferenceOutcome get() {
            int index = calls.getAndIncrement();
            return outcomes.get(Math.min(index, outcomes.size() - 1));
        }

        int calls() {
            return calls.get();
        }
    }
}
