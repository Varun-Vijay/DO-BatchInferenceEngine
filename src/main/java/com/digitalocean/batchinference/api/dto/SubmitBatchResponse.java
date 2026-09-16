package com.digitalocean.batchinference.api.dto;

public record SubmitBatchResponse(String batchId, int promptCount, String statusUrl) {
}
