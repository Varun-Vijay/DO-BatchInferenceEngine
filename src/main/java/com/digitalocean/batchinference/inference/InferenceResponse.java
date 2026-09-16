package com.digitalocean.batchinference.inference;

public record InferenceResponse(String completion, int tokens) {
}
