package dev.gautamgupta.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.cache.EmbeddingClient;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import reactor.core.publisher.Mono;

/**
 * Semantic-cache embeddings through whichever provider {@code gateway.cache.semantic.embedding-provider}
 * names (ADR-0006). A missing key or a provider that cannot embed makes {@link #available()} false,
 * and the cache treats that as a miss.
 */
public final class RoutingEmbeddingClient implements EmbeddingClient {

    private final ProviderClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int dimensions;
    private final boolean available;

    public RoutingEmbeddingClient(
            ProviderRegistry registry,
            ObjectMapper objectMapper,
            String provider,
            String model,
            int dimensions
    ) {
        this.objectMapper = objectMapper;
        this.model = model == null || model.isBlank() ? "text-embedding-3-small" : model;
        this.dimensions = dimensions;
        this.client = resolve(registry, provider);
        this.available = this.client != null;
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

    private static ProviderClient resolve(ProviderRegistry registry, String provider) {
        if (registry == null || provider == null || provider.isBlank()) {
            return null;
        }
        try {
            ProviderClient client = registry.get(provider);
            if (!client.capabilities().embeddings()) {
                return null;
            }
            client.ensureConfigured();
            return client;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
