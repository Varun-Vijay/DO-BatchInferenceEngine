package com.digitalocean.batchinference.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SubmitBatchRequest(
        @NotEmpty(message = "prompts must not be empty")
        List<@NotBlank(message = "prompt must not be blank") String> prompts
) {
}
