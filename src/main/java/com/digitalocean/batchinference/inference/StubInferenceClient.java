package com.digitalocean.batchinference.inference;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Placeholder client that lets the scheduling core run end-to-end without a real
 * endpoint. It is {@code @Primary} so that adding an HTTP client alongside it does not
 * break the context; drop the annotation here once the real client is wired in.
 */
@Primary
@Component
public class StubInferenceClient implements InferenceClient {

    private static final Logger log = LoggerFactory.getLogger(StubInferenceClient.class);
    private static final long SIMULATED_LATENCY_MS = 100;

    @PostConstruct
    void warnStubIsActive() {
        log.warn("StubInferenceClient is active — prompts are echoed, no real inference is performed");
    }

    @Override
    public InferenceOutcome infer(InferenceRequest request) {
        try {
            Thread.sleep(SIMULATED_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InferenceOutcome.TransientFailure("interrupted while calling stub client");
        }
        return new InferenceOutcome.Success("[stub] echo: " + request.prompt(), 0, SIMULATED_LATENCY_MS);
    }
}
