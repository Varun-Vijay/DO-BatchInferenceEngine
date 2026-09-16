package com.digitalocean.batchinference.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String message, List<String> details) {

    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, null);
    }
}
