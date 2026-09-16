package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.inference.InferenceOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Placeholder executor: calls once and reports the outcome verbatim. Superseded as the
 * default by {@link DefaultRetryExecutor}; registered only when {@code retry.executor=none},
 * so retries can be taken out of the picture when diagnosing the scheduling core.
 */
@Component
@ConditionalOnProperty(prefix = "retry", name = "executor", havingValue = "none")
public class NoRetryExecutor implements RetryExecutor {

    @Override
    public RetryResult execute(Supplier<InferenceOutcome> call) {
        return new RetryResult(call.get(), 1, 0L);
    }
}
