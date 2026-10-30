package dev.gautamgupta.gateway.cache;

import reactor.core.publisher.Mono;

/**
 * Embedding call used by the semantic cache. Week 4 wires this to the OpenAI
 * WebClient adapter. Ollama is deferred. The public {@code /v1/embeddings} route
 * is not this type.
 */
public interface EmbeddingClient {

    Mono<float[]> embed(String text);

    /** False when the configured provider cannot be called. Semantic lookup stays off. */
    default boolean available() {
        return true;
    }
}
