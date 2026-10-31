package dev.gautamgupta.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.cache.EmbeddingClient;
import dev.gautamgupta.gateway.providers.openai.OpenAiProviderClient;
import reactor.core.publisher.Mono;

/**
 * Semantic-cache embeddings via the OpenAI WebClient adapter.
 * {@code POST /v1/embeddings} is not exposed by the gateway.
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {

    private final OpenAiProviderClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int dimensions;
    private final boolean available;

    public OpenAiEmbeddingClient(
            OpenAiProviderClient client,
            ObjectMapper objectMapper,
            String provider,
            String model,
            int dimensions
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        this.dimensions = dimensions;
        this.available = "openai".equals(provider) && configured(client);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Mono<float[]> embed(String text) {
        if (!available) {
            return Mono.error(new IllegalStateException("embeddings are not configured"));
        }
        EmbeddingRequest request = new EmbeddingRequest(
                model, objectMapper.getNodeFactory().textNode(text), dimensions);
        return client.embed(request).map(this::vector);
    }

    private float[] vector(EmbeddingResponse response) {
        JsonNode data = response == null ? null : response.data();
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embedding response was empty");
        }
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("embedding response had no vector");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    private static boolean configured(OpenAiProviderClient client) {
        try {
            client.ensureConfigured();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
