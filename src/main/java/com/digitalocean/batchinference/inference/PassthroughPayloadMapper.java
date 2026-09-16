package com.digitalocean.batchinference.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Mapper for the neutral wire shape: {@code {"prompt": ...}} out,
 * {@code {"completion": ..., "tokens": ...}} back. The domain records already match it,
 * so there is nothing to translate. The seam exists so that swapping in a vendor payload
 * shape later touches only this class.
 */
@Component
public class PassthroughPayloadMapper implements InferencePayloadMapper {

    private final ObjectMapper objectMapper;

    public PassthroughPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object toWirePayload(InferenceRequest request) {
        return request;
    }

    @Override
    public InferenceResponse fromWirePayload(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, InferenceResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("response body is not a valid inference response", e);
        }
    }
}
