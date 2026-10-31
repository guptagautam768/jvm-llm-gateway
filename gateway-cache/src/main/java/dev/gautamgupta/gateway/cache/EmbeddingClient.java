package dev.gautamgupta.gateway.cache;

import reactor.core.publisher.Mono;

/**
 * Embedding call used by the semantic cache. The public {@code POST /v1/embeddings}
 * route is a separate API and does not go through this type.
 */
public interface EmbeddingClient {

    Mono<float[]> embed(String text);

    /** False when the configured provider cannot be called. Semantic lookup stays off. */
    default boolean available() {
        return true;
    }
}
