package dev.gautamgupta.gateway.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Token counts shared by chat completions and embeddings.
 * Null counts are omitted on the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
