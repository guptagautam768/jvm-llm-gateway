package dev.gautamgupta.gateway.api.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Embeddings request (API-021). The HTTP route is not exposed in week 1;
 * the type exists so {@code ProviderClient.embed} can be implemented later
 * without changing the port.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingRequest(String model, JsonNode input, Integer dimensions) {

    public EmbeddingRequest(String model, JsonNode input) {
        this(model, input, null);
    }
}
