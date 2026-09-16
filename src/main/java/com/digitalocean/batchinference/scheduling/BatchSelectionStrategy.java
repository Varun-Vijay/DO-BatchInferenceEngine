package com.digitalocean.batchinference.scheduling;

import java.util.concurrent.BlockingQueue;

/**
 * Decides which batch the single dispatcher thread picks up next, and whether that
 * batch must fully drain before another one starts.
 */
public interface BatchSelectionStrategy {

    /** Blocks until a batch is available. */
    String selectNext(BlockingQueue<String> pending) throws InterruptedException;

    /** When true, the dispatcher waits for the selected batch to finish before selecting again. */
    boolean completeBatchBeforeNext();
}
