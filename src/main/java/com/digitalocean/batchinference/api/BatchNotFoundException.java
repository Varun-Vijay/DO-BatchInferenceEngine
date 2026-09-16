package com.digitalocean.batchinference.api;

public class BatchNotFoundException extends RuntimeException {

    public BatchNotFoundException(String batchId) {
        super("No batch found with id " + batchId);
    }
}
