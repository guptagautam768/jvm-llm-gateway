package dev.gautamgupta.gateway.api.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.gautamgupta.gateway.api.Usage;
import java.util.List;

/** One SSE chunk for {@code stream=true} (API-013). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionChunk(
        String id,
        String object,
        Long created,
        String model,
        List<Choice> choices,
        Usage usage,
        String systemFingerprint
) {

    public ChatCompletionChunk {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Integer index, Delta delta, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, JsonNode content, JsonNode toolCalls) {
    }
}
