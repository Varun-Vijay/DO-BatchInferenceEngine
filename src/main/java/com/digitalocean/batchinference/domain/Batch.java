package com.digitalocean.batchinference.domain;

import java.time.Instant;

/**
 * A submitted group of prompts. Immutable: status transitions produce a new instance.
 */
public record Batch(
        String id,
        BatchStatus status,
        int totalPrompts,
        Instant submittedAt,
        Instant completedAt
) {

    public Batch withStatus(BatchStatus newStatus, Instant newCompletedAt) {
        return new Batch(id, newStatus, totalPrompts, submittedAt, newCompletedAt);
    }
}
