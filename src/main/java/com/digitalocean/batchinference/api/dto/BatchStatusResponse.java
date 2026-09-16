package com.digitalocean.batchinference.api.dto;

import com.digitalocean.batchinference.domain.BatchStatus;
import com.digitalocean.batchinference.domain.TaskStatus;

import java.time.Instant;
import java.util.Map;

public record BatchStatusResponse(
        String batchId,
        BatchStatus status,
        Instant submittedAt,
        Instant completedAt,
        Map<TaskStatus, Integer> counts,
        int progressPercent,
        int totalAttempts
) {
}
