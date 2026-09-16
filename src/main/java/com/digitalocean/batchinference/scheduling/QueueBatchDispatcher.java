package com.digitalocean.batchinference.scheduling;

import com.digitalocean.batchinference.config.ConcurrencyProperties;
import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.PromptTask;
import com.digitalocean.batchinference.domain.TaskStatus;
import com.digitalocean.batchinference.inference.InferenceClient;
import com.digitalocean.batchinference.inference.InferenceOutcome;
import com.digitalocean.batchinference.inference.InferenceRequest;
import com.digitalocean.batchinference.repository.BatchRepository;
import com.digitalocean.batchinference.retry.RetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduling core.
 *
 * <p>Two independent limits shape the execution order. The {@link CountDownLatch} the
 * dispatcher waits on gives head-of-line FIFO ordering <em>between</em> batches; the
 * {@link Semaphore} caps concurrent calls <em>within and across</em> batches. Neither
 * substitutes for the other.
 */
@Component
public class QueueBatchDispatcher implements BatchDispatcher, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueBatchDispatcher.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final BlockingQueue<String> pending;
    private final BatchSelectionStrategy selectionStrategy;
    private final BatchRepository repository;
    private final InferenceClient inferenceClient;
    private final RetryExecutor retryExecutor;
    private final ExecutorService taskExecutor;
    private final Semaphore inFlightSemaphore;

    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Thread dispatcherThread;

    public QueueBatchDispatcher(ConcurrencyProperties concurrencyProperties,
                                BatchSelectionStrategy selectionStrategy,
                                BatchRepository repository,
                                InferenceClient inferenceClient,
                                RetryExecutor retryExecutor,
                                ExecutorService taskExecutorService,
                                Semaphore inFlightSemaphore) {
        this.pending = new LinkedBlockingQueue<>(concurrencyProperties.batchQueueCapacity());
        this.selectionStrategy = selectionStrategy;
        this.repository = repository;
        this.inferenceClient = inferenceClient;
        this.retryExecutor = retryExecutor;
        this.taskExecutor = taskExecutorService;
        this.inFlightSemaphore = inFlightSemaphore;
    }

    @Override
    public boolean enqueue(String batchId) {
        if (!accepting.get()) {
            log.warn("Rejecting batchId={} — dispatcher is not accepting work", batchId);
            return false;
        }
        boolean queued = pending.offer(batchId);
        if (queued) {
            log.info("Queued batchId={} (queueDepth={})", batchId, pending.size());
        } else {
            log.warn("Rejecting batchId={} — pending queue is full", batchId);
        }
        return queued;
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void start() {
        accepting.set(true);
        dispatcherThread = new Thread(this::dispatchLoop, "batch-dispatcher");
        dispatcherThread.start();
        log.info("Batch dispatcher started (queueCapacity={}, maxInFlight={})",
                pending.remainingCapacity(), inFlightSemaphore.availablePermits());
    }

    @Override
    public void stop() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        log.info("Batch dispatcher stopping — {} batches left unprocessed", pending.size());
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Tasks still running after {}s — forcing shutdown", SHUTDOWN_GRACE.toSeconds());
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskExecutor.shutdownNow();
        }
        Thread thread = dispatcherThread;
        if (thread != null) {
            thread.interrupt();
        }
        log.info("Batch dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return accepting.get();
    }

    /**
     * Stop after the web server does, so no new submission can race the drain. Lifecycle
     * beans are stopped from the highest phase downwards.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2048;
    }

    // --- dispatching ----------------------------------------------------------------

    private void dispatchLoop() {
        while (accepting.get()) {
            String batchId;
            try {
                batchId = selectionStrategy.selectNext(pending);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                processBatch(batchId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Dispatch failed for batchId={}", batchId, e);
            }
        }
        log.info("Dispatcher loop exiting");
    }

    private void processBatch(String batchId) throws InterruptedException {
        List<PromptTask> tasks = repository.findTasks(batchId);
        if (tasks.isEmpty()) {
            log.warn("Skipping batchId={} — no tasks found", batchId);
            return;
        }
        repository.updateBatchStatus(batchId, BatchStatus.IN_PROGRESS, null);
        log.info("Batch batchId={} IN_PROGRESS with {} tasks", batchId, tasks.size());

        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (PromptTask task : tasks) {
            try {
                taskExecutor.execute(() -> runTask(task, latch));
            } catch (RejectedExecutionException e) {
                log.warn("batchId={} inputIndex={} not started — executor is shut down",
                        batchId, task.inputIndex());
                repository.updateTask(task.withFailure("service shut down before task started", 0, Instant.now()));
                latch.countDown();
            }
        }

        if (selectionStrategy.completeBatchBeforeNext()) {
            latch.await();
            finalizeBatch(batchId);
        } else {
            taskExecutor.execute(() -> awaitAndFinalize(batchId, latch));
        }
    }

    private void awaitAndFinalize(String batchId, CountDownLatch latch) {
        try {
            latch.await();
            finalizeBatch(batchId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while finalizing batchId={}", batchId);
        }
    }

    private void finalizeBatch(String batchId) {
        boolean anyFailed = repository.findTasks(batchId).stream()
                .anyMatch(task -> task.status() == TaskStatus.FAILED);
        BatchStatus status = anyFailed ? BatchStatus.COMPLETED_WITH_ERRORS : BatchStatus.COMPLETED;
        repository.updateBatchStatus(batchId, status, Instant.now());
        log.info("Batch batchId={} finished with status={}", batchId, status);
    }

    // --- task execution -------------------------------------------------------------

    private void runTask(PromptTask task, CountDownLatch latch) {
        try {
            inFlightSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.updateTask(task.withFailure("interrupted before start", 0, Instant.now()));
            latch.countDown();
            return;
        }

        PromptTask running = task.withStarted(Instant.now());
        int concurrent = inFlight.incrementAndGet();
        try {
            repository.updateTask(running);
            log.info("batchId={} inputIndex={} IN_PROGRESS (inFlight={})",
                    running.batchId(), running.inputIndex(), concurrent);

            RetryExecutor.RetryResult result =
                    retryExecutor.execute(() -> inferenceClient.infer(new InferenceRequest(running.prompt())));
            PromptTask settled = applyOutcome(running, result);
            repository.updateTask(settled);
            log.info("batchId={} inputIndex={} {} after {} attempt(s){}",
                    settled.batchId(), settled.inputIndex(), settled.status(), settled.attempts(),
                    settled.failureReason() == null ? "" : ": " + settled.failureReason());
        } catch (RuntimeException e) {
            log.error("batchId={} inputIndex={} FAILED unexpectedly",
                    running.batchId(), running.inputIndex(), e);
            repository.updateTask(running.withFailure("unexpected error: " + e, 1, Instant.now()));
        } finally {
            inFlight.decrementAndGet();
            inFlightSemaphore.release();
            latch.countDown();
        }
    }

    private PromptTask applyOutcome(PromptTask task, RetryExecutor.RetryResult result) {
        Instant now = Instant.now();
        int attempts = result.attempts();
        return switch (result.finalOutcome()) {
            case InferenceOutcome.Success success ->
                    task.withSuccess(success.completion(), attempts, now);
            case InferenceOutcome.RateLimited rateLimited ->
                    task.withFailure("rate limited by inference endpoint" + hintSuffix(rateLimited), attempts, now);
            case InferenceOutcome.TransientFailure transientFailure ->
                    task.withFailure("transient failure: " + transientFailure.reason(), attempts, now);
            case InferenceOutcome.TerminalFailure terminal ->
                    task.withFailure("terminal failure (HTTP %d): %s".formatted(terminal.statusCode(), terminal.reason()),
                            attempts, now);
        };
    }

    private String hintSuffix(InferenceOutcome.RateLimited rateLimited) {
        return rateLimited.retryAfterHint() == null
                ? ""
                : " (retry after " + rateLimited.retryAfterHint().toMillis() + "ms)";
    }
}
