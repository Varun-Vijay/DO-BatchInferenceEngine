package com.digitalocean.batchinference.domain;

import java.time.Instant;

/**
 * A single prompt within a batch. Immutable: the {@code withX} helpers return a new
 * instance, and {@code BatchRepository.updateTask} swaps the stored record wholesale.
 */
public record PromptTask(
        String id,
        String batchId,
        int inputIndex,
        String prompt,
        TaskStatus status,
        String completion,
        String failureReason,
        int attempts,
        Instant startedAt,
        Instant finishedAt
) {

    public static PromptTask pending(String id, String batchId, int inputIndex, String prompt) {
        return new PromptTask(id, batchId, inputIndex, prompt, TaskStatus.PENDING, null, null, 0, null, null);
    }

    public PromptTask withStarted(Instant startedAt) {
        return new PromptTask(id, batchId, inputIndex, prompt, TaskStatus.IN_PROGRESS,
                completion, failureReason, attempts, startedAt, finishedAt);
    }

    public PromptTask withSuccess(String completion, int attempts, Instant finishedAt) {
        return new PromptTask(id, batchId, inputIndex, prompt, TaskStatus.SUCCEEDED,
                completion, null, attempts, startedAt, finishedAt);
    }

    public PromptTask withFailure(String failureReason, int attempts, Instant finishedAt) {
        return new PromptTask(id, batchId, inputIndex, prompt, TaskStatus.FAILED,
                null, failureReason, attempts, startedAt, finishedAt);
    }
}
