package com.digitalocean.batchinference.api.dto;

import com.digitalocean.batchinference.domain.TaskStatus;

import java.util.List;

public record BatchResultsResponse(Summary summary, List<Result> results) {

    public record Summary(int total, int succeeded, int failed, int totalAttempts, long durationMs) {
    }

    public record Result(
            int inputIndex,
            String prompt,
            TaskStatus status,
            String completion,
            String failureReason,
            int attempts
    ) {
    }
}
