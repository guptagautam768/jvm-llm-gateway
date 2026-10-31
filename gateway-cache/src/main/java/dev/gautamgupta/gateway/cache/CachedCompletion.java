package dev.gautamgupta.gateway.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;

/**
 * What Redis stores. The response {@code model} is the name the client sent.
 * {@code provider} and {@code model} are the upstream pair that produced it, for the ledger.
 * The prompt is not stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CachedCompletion(String provider, String model, ChatCompletionResponse response) {
}
