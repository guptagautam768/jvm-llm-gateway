package dev.gautamgupta.gateway.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.gautamgupta.gateway.api.Usage;
import java.util.List;

/** Non-streaming chat completion (API-012). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage,
        String systemFingerprint
) {

    public ChatCompletionResponse {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Integer index, AssistantMessage message, String finishReason, JsonNode logprobs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssistantMessage(String role, JsonNode content, JsonNode toolCalls, String refusal) {
    }
}
