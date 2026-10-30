package dev.gautamgupta.gateway.core.provider;

import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.api.model.ModelListResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Gateway-owned provider port (ADR-0004).
 *
 * <p>Core and the API modules never see a provider SDK type. OpenAI and Anthropic
 * implement chat and streaming. Ollama is reserved. {@link #embed} is on the port
 * so embeddings (FR-002) do not require a signature change.
 */
public interface ProviderClient {

    String id();

    ProviderCapabilities capabilities();

    /**
     * Fail before the HTTP response is committed when the provider cannot be called
     * (for example a missing API key).
     */
    default void ensureConfigured() {
    }

    Mono<ChatCompletionResponse> complete(ChatCompletionRequest request);

    Flux<ChatCompletionChunk> stream(ChatCompletionRequest request);

    default Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return Mono.error(ProviderException.unsupported(id(), "embeddings"));
    }

    default Mono<ModelListResponse> listModels() {
        return Mono.error(ProviderException.unsupported(id(), "models"));
    }
}
