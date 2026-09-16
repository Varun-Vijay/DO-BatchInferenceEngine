package com.digitalocean.batchinference.api;

import com.digitalocean.batchinference.api.dto.BatchResultsResponse;
import com.digitalocean.batchinference.api.dto.BatchStatusResponse;
import com.digitalocean.batchinference.api.dto.ErrorResponse;
import com.digitalocean.batchinference.api.dto.SubmitBatchRequest;
import com.digitalocean.batchinference.api.dto.SubmitBatchResponse;
import com.digitalocean.batchinference.config.ConcurrencyProperties;
import com.digitalocean.batchinference.domain.Batch;
import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.PromptTask;
import com.digitalocean.batchinference.domain.TaskStatus;
import com.digitalocean.batchinference.repository.BatchRepository;
import com.digitalocean.batchinference.scheduling.BatchDispatcher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final BatchRepository repository;
    private final BatchDispatcher dispatcher;
    private final ConcurrencyProperties concurrencyProperties;

    public BatchController(BatchRepository repository,
                           BatchDispatcher dispatcher,
                           ConcurrencyProperties concurrencyProperties) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.concurrencyProperties = concurrencyProperties;
    }

    @PostMapping
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitBatchRequest request) {
        int promptCount = request.prompts().size();
        if (promptCount > concurrencyProperties.maxBatchSize()) {
            throw new IllegalArgumentException(
                    "prompts exceeds the maximum batch size of " + concurrencyProperties.maxBatchSize());
        }

        String batchId = UUID.randomUUID().toString();
        Batch batch = new Batch(batchId, BatchStatus.PENDING, promptCount, Instant.now(), null);
        List<PromptTask> tasks = buildTasks(batchId, request.prompts());

        // Persist before enqueueing: the 202 must only ever describe work that is
        // already recorded, so a dispatcher failure can never lose an accepted batch.
        repository.saveBatch(batch, tasks);

        if (!dispatcher.enqueue(batchId)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("queue_full", "Batch queue is full, retry later"));
        }

        return ResponseEntity.accepted()
                .body(new SubmitBatchResponse(batchId, promptCount, statusUrl(batchId)));
    }

    @GetMapping("/{id}")
    public BatchStatusResponse status(@PathVariable String id) {
        Batch batch = repository.findBatch(id).orElseThrow(() -> new BatchNotFoundException(id));
        List<PromptTask> tasks = repository.findTasks(id);

        Map<TaskStatus, Integer> counts = new EnumMap<>(TaskStatus.class);
        for (TaskStatus status : TaskStatus.values()) {
            counts.put(status, 0);
        }
        int totalAttempts = 0;
        for (PromptTask task : tasks) {
            counts.merge(task.status(), 1, Integer::sum);
            totalAttempts += task.attempts();
        }
        int settled = counts.get(TaskStatus.SUCCEEDED) + counts.get(TaskStatus.FAILED);
        int progressPercent = tasks.isEmpty() ? 100 : settled * 100 / tasks.size();

        return new BatchStatusResponse(batch.id(), batch.status(), batch.submittedAt(), batch.completedAt(),
                counts, progressPercent, totalAttempts);
    }

    @GetMapping("/{id}/results")
    public BatchResultsResponse results(@PathVariable String id) {
        Batch batch = repository.findBatch(id).orElseThrow(() -> new BatchNotFoundException(id));
        List<PromptTask> tasks = repository.findTasks(id);

        int succeeded = 0;
        int failed = 0;
        int totalAttempts = 0;
        for (PromptTask task : tasks) {
            if (task.status() == TaskStatus.SUCCEEDED) {
                succeeded++;
            } else if (task.status() == TaskStatus.FAILED) {
                failed++;
            }
            totalAttempts += task.attempts();
        }

        Instant end = batch.completedAt() == null ? Instant.now() : batch.completedAt();
        long durationMs = Duration.between(batch.submittedAt(), end).toMillis();

        List<BatchResultsResponse.Result> results = tasks.stream()
                .map(task -> new BatchResultsResponse.Result(task.inputIndex(), task.prompt(), task.status(),
                        task.completion(), task.failureReason(), task.attempts()))
                .toList();

        return new BatchResultsResponse(
                new BatchResultsResponse.Summary(tasks.size(), succeeded, failed, totalAttempts, durationMs),
                results);
    }

    private static List<PromptTask> buildTasks(String batchId, List<String> prompts) {
        return IntStream.range(0, prompts.size())
                .mapToObj(index -> PromptTask.pending(
                        UUID.randomUUID().toString(), batchId, index, prompts.get(index)))
                .toList();
    }

    private static String statusUrl(String batchId) {
        return "/api/v1/batches/" + batchId;
    }
}
