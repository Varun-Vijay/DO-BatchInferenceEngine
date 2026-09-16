package com.digitalocean.batchinference.repository;

import com.digitalocean.batchinference.domain.Batch;
import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.PromptTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for batches and their tasks. Implementations must hand back
 * detached values only, so that a JDBC-backed implementation is a drop-in swap.
 */
public interface BatchRepository {

    void saveBatch(Batch batch, List<PromptTask> tasks);

    Optional<Batch> findBatch(String batchId);

    /** Tasks of the batch ordered by {@code inputIndex}; empty if the batch is unknown. */
    List<PromptTask> findTasks(String batchId);

    Optional<PromptTask> findTask(String taskId);

    void updateBatchStatus(String batchId, BatchStatus status, Instant completedAt);

    void updateTask(PromptTask task);
}
