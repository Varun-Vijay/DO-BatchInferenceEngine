package com.digitalocean.batchinference.inference;

import com.digitalocean.batchinference.config.InferenceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire contract with {@code POST https://inference.do-ai.run/v1/chat/completions}.
 * A drift in field names here is invisible at compile time and shows up only as a 400
 * from the endpoint, so the serialized JSON is asserted key by key.
 */
class ChatCompletionsPayloadMapperTest {

    private static final String MODEL = "llama3.3-70b-instruct";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatCompletionsPayloadMapper mapper =
            new ChatCompletionsPayloadMapper(objectMapper, properties());

    private static InferenceProperties properties() {
        return new InferenceProperties("https://inference.do-ai.run/v1", "/chat/completions",
                "test-key", MODEL, 256, 0.7, Duration.ofSeconds(2), Duration.ofSeconds(60),
                "http", "chat-completions");
    }

    @Test
    void requestSerializesToTheOpenAiChatShape() throws Exception {
        JsonNode payload = objectMapper.valueToTree(
                mapper.toWirePayload(new InferenceRequest("What is the capital of Portugal?")));

        assertThat(payload.get("model").asText()).isEqualTo(MODEL);
        assertThat(payload.get("max_completion_tokens").asInt()).isEqualTo(256);
        assertThat(payload.get("temperature").asDouble()).isEqualTo(0.7);
        // Streaming would arrive as server-sent events, which the client cannot read.
        assertThat(payload.get("stream").asBoolean()).isFalse();

        JsonNode messages = payload.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("What is the capital of Portugal?");
    }

    @Test
    void responseYieldsTheFirstChoiceContentAndCompletionTokens() {
        String body = """
                {
                  "id": "chatcmpl-abc123",
                  "object": "chat.completion",
                  "created": 1677649420,
                  "model": "llama3.3-70b-instruct",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {"role": "assistant", "content": "Lisbon.", "refusal": null}
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
                }
                """;

        InferenceResponse response = mapper.fromWirePayload(body);

        assertThat(response.completion()).isEqualTo("Lisbon.");
        assertThat(response.tokens()).isEqualTo(20);
    }

    @Test
    void unknownFieldsAndAbsentUsageDoNotFailTheParse() {
        String body = """
                {
                  "choices": [{"index": 0, "finish_reason": "stop",
                    "message": {"role": "assistant", "content": "Lisbon.", "reasoning_content": "thinking"}}],
                  "some_future_field": {"nested": true}
                }
                """;

        InferenceResponse response = mapper.fromWirePayload(body);

        assertThat(response.completion()).isEqualTo("Lisbon.");
        assertThat(response.tokens()).isZero();
    }

    /**
     * A refusal is a 200 with a null content. Returning it as an empty success would
     * record a blank completion against the task and report it as done.
     */
    @Test
    void choiceWithoutContentIsRejectedRatherThanTreatedAsAnEmptyCompletion() {
        String body = """
                {"choices": [{"index": 0, "finish_reason": "content_filter",
                  "message": {"role": "assistant", "content": null, "refusal": "I cannot help with that."}}]}
                """;

        assertThatThrownBy(() -> mapper.fromWirePayload(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content_filter");
    }

    @Test
    void emptyChoicesIsRejected() {
        assertThatThrownBy(() -> mapper.fromWirePayload("{\"choices\": []}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no choices");
    }

    @Test
    void nonJsonBodyIsRejected() {
        assertThatThrownBy(() -> mapper.fromWirePayload("<html>502 Bad Gateway</html>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a chat completion");
    }
}
