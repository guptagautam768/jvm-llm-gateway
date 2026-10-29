package dev.gautamgupta.gateway.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * OpenAI chat-completions request (API-011).
 *
 * <p>Modeled fields are the ones common OpenAI SDKs send. Any other field is ignored on
 * read and is not forwarded (FR-001, "ignored" rather than rejected). Message {@code content}
 * stays a {@link JsonNode} so both a string and an array of parts round-trip.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Double topP,
        Integer n,
        Boolean stream,
        StreamOptions streamOptions,
        JsonNode stop,
        Integer maxTokens,
        Integer maxCompletionTokens,
        Double presencePenalty,
        Double frequencyPenalty,
        String user,
        JsonNode responseFormat,
        Integer seed,
        JsonNode tools,
        JsonNode toolChoice,
        Boolean parallelToolCalls,
        Boolean logprobs,
        Integer topLogprobs
) {

    public ChatCompletionRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public ChatCompletionRequest withStream(Boolean stream) {
        return new ChatCompletionRequest(
                model,
                messages,
                temperature,
                topP,
                n,
                stream,
                streamOptions,
                stop,
                maxTokens,
                maxCompletionTokens,
                presencePenalty,
                frequencyPenalty,
                user,
                responseFormat,
                seed,
                tools,
                toolChoice,
                parallelToolCalls,
                logprobs,
                topLogprobs);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(
            String role,
            JsonNode content,
            String name,
            JsonNode toolCalls,
            String toolCallId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamOptions(Boolean includeUsage) {
    }
}
