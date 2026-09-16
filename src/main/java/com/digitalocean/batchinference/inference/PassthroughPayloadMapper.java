package com.digitalocean.batchinference.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mapper for the neutral wire shape: {@code {"prompt": ...}} out,
 * {@code {"completion": ..., "tokens": ...}} back. The domain records already match it,
 * so there is nothing to translate.
 *
 * <p>This is what the in-app mock at {@code /mock/v1/infer} speaks. The real endpoint
 * wants the OpenAI shape — see {@link ChatCompletionsPayloadMapper}, which is the
 * default. Select this one with {@code inference.payload-format=passthrough}.
 */
@Component
@ConditionalOnProperty(prefix = "inference", name = "payload-format", havingValue = "passthrough")
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
