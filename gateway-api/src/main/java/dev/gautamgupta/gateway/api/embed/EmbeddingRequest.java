package dev.gautamgupta.gateway.api.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Embeddings request (API-021). {@code encoding_format} and {@code user} are ignored
 * and are not forwarded (FR-001).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingRequest(String model, JsonNode input, Integer dimensions) {

    public EmbeddingRequest(String model, JsonNode input) {
        this(model, input, null);
    }

    public EmbeddingRequest withModel(String model) {
        return new EmbeddingRequest(model, input, dimensions);
    }

    public EmbeddingRequest withInput(JsonNode input) {
        return new EmbeddingRequest(model, input, dimensions);
    }
}
