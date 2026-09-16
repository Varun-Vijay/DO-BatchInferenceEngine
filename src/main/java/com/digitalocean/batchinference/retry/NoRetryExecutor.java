package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.inference.InferenceOutcome;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Placeholder executor: calls once and reports the outcome verbatim. Superseded as the
 * default by {@link DefaultRetryExecutor}; kept so retries can be taken out of the
 * picture when diagnosing the scheduling core.
 */
@Component
public class NoRetryExecutor implements RetryExecutor {

    @Override
    public RetryResult execute(Supplier<InferenceOutcome> call) {
        return new RetryResult(call.get(), 1, 0L);
    }
}
