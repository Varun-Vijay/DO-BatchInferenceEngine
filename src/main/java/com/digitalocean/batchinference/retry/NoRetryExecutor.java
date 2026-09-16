package com.digitalocean.batchinference.retry;

import com.digitalocean.batchinference.inference.InferenceOutcome;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Placeholder executor: calls once and reports the outcome verbatim. It is
 * {@code @Primary} so a real backoff-driven executor can be added beside it without
 * breaking the context; drop the annotation here once that executor exists.
 */
@Primary
@Component
public class NoRetryExecutor implements RetryExecutor {

    @Override
    public RetryResult execute(Supplier<InferenceOutcome> call) {
        return new RetryResult(call.get(), 1, 0L);
    }
}
