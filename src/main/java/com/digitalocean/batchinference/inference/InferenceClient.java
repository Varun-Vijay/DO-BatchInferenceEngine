package com.digitalocean.batchinference.inference;

public interface InferenceClient {

    /** Never throws for expected failures — always returns an outcome. */
    InferenceOutcome infer(InferenceRequest request);
}
