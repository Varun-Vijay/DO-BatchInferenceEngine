package com.digitalocean.batchinference.scheduling;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;

/**
 * Strict submission order: batches are served one at a time, first come first served.
 */
@Component
public class FifoBatchSelectionStrategy implements BatchSelectionStrategy {

    @Override
    public String selectNext(BlockingQueue<String> pending) throws InterruptedException {
        return pending.take();
    }

    @Override
    public boolean completeBatchBeforeNext() {
        return true;
    }
}
