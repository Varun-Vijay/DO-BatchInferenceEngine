package com.digitalocean.batchinference.inference;

import com.digitalocean.batchinference.config.InferenceProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Speaks the OpenAI-compatible shape that DigitalOcean serverless inference expects at
 * {@code POST /v1/chat/completions}.
 *
 * <p>A prompt task is a single stateless turn, so every request carries exactly one
 * {@code user} message: the endpoint holds no session, and anything the model needs to
 * see has to be in the body.
 */
@Component
@ConditionalOnProperty(prefix = "inference", name = "payload-format",
        havingValue = "chat-completions", matchIfMissing = true)
public class ChatCompletionsPayloadMapper implements InferencePayloadMapper {

    private static final String ROLE_USER = "user";

    private final ObjectMapper objectMapper;
    private final InferenceProperties properties;

    public ChatCompletionsPayloadMapper(ObjectMapper objectMapper, InferenceProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Object toWirePayload(InferenceRequest request) {
        return new ChatCompletionRequest(
                properties.model(),
                List.of(new Message(ROLE_USER, request.prompt())),
                properties.maxCompletionTokens(),
                properties.temperature(),
                false);
    }

    /**
     * Reads the first choice. {@code n} is never sent, so the endpoint returns exactly
     * one; a response without choices means the model produced nothing usable and is
     * treated as unreadable rather than as an empty success.
     */
    @Override
    public InferenceResponse fromWirePayload(String responseBody) {
        ChatCompletionResponse response;
        try {
            response = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("response body is not a chat completion", e);
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalArgumentException("chat completion carried no choices");
        }
        Choice choice = response.choices().getFirst();
        if (choice.message() == null || choice.message().content() == null) {
            // A refusal or a content filter lands here: the call succeeded, but there is
            // no completion to record, and silently storing "" would look like success.
            throw new IllegalArgumentException("chat completion choice carried no content"
                    + (choice.finishReason() == null ? "" : " (finish_reason=" + choice.finishReason() + ")"));
        }
        return new InferenceResponse(choice.message().content(), completionTokens(response));
    }

    /** Usage is optional on the wire; 0 means "not reported", not "nothing generated". */
    private static int completionTokens(ChatCompletionResponse response) {
        return response.usage() == null ? 0 : response.usage().completionTokens();
    }

    // --- wire records ----------------------------------------------------------------

    record ChatCompletionRequest(
            String model,
            List<Message> messages,
            @JsonProperty("max_completion_tokens") int maxCompletionTokens,
            double temperature,
            boolean stream) {
    }

    /** Serialized as a request message and deserialized as the assistant reply. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatCompletionResponse(List<Choice> choices, Usage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("completion_tokens") int completionTokens) {
    }
}
