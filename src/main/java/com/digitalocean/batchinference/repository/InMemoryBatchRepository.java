package com.digitalocean.batchinference.repository;

import com.digitalocean.batchinference.domain.Batch;
import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.PromptTask;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link BatchRepository}. Every returned collection is a snapshot copy, and
 * the stored values are immutable records, so callers can never mutate repository state
 * by accident.
 */
@Repository
public class InMemoryBatchRepository implements BatchRepository {

    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final Map<String, PromptTask> tasksById = new ConcurrentHashMap<>();
    private final Map<String, List<String>> taskIdsByBatch = new ConcurrentHashMap<>();

    @Override
    public void saveBatch(Batch batch, List<PromptTask> tasks) {
        tasks.forEach(task -> tasksById.put(task.id(), task));
        taskIdsByBatch.put(batch.id(), tasks.stream().map(PromptTask::id).toList());
        batches.put(batch.id(), batch);
    }a

    @Override
    public Optional<Batch> findBatch(String batchId) {
        return Optional.ofNullable(batches.get(batchId));
    }

    @Override
    public List<PromptTask> findTasks(String batchId) {
        return taskIdsByBatch.getOrDefault(batchId, List.of()).stream()
                .map(tasksById::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(PromptTask::inputIndex))
                .toList();
    }

    @Override
    public Optional<PromptTask> findTask(String taskId) {
        return Optional.ofNullable(tasksById.get(taskId));
    }

    @Override
    public void updateBatchStatus(String batchId, BatchStatus status, Instant completedAt) {
        batches.computeIfPresent(batchId, (id, existing) -> existing.withStatus(status, completedAt));
    }

    @Override
    public void updateTask(PromptTask task) {
        tasksById.replace(task.id(), task);
    }
}
