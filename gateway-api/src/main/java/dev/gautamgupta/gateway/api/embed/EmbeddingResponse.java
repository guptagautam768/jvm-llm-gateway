package dev.gautamgupta.gateway.api.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.gautamgupta.gateway.api.Usage;

/** Embeddings response (API-022). {@code data} stays a tree until the route is implemented. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(String object, JsonNode data, String model, Usage usage) {
}
