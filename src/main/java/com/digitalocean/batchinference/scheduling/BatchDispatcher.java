package com.digitalocean.batchinference.scheduling;

public interface BatchDispatcher {

    /** Hands a persisted batch to the scheduler. Non-blocking; false if the queue is full. */
    boolean enqueue(String batchId);
}
