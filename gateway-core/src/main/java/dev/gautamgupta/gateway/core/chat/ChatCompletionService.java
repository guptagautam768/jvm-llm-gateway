package dev.gautamgupta.gateway.core.chat;

import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Week-1 pipeline: validate, then forward to the single OpenAI provider.
 * Alias resolution, fallback, cache, and guardrails plug in around {@link #resolve}
 * without changing the HTTP layer.
 */
public final class ChatCompletionService {

    private final ProviderRegistry registry;

    public ChatCompletionService(ProviderRegistry registry) {
        this.registry = registry;
    }

    public void validate(ChatCompletionRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body is required.", null);
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new InvalidRequestException("model is required.", "model");
        }
        if (request.messages().isEmpty()) {
            throw new InvalidRequestException("messages is required.", "messages");
        }
        for (int i = 0; i < request.messages().size(); i++) {
            ChatCompletionRequest.ChatMessage message = request.messages().get(i);
            if (message == null || message.role() == null || message.role().isBlank()) {
                throw new InvalidRequestException("message role is required.", "messages[" + i + "].role");
            }
        }
        resolve(request).ensureConfigured();
    }

    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        return resolve(request).complete(request.withStream(null));
    }

    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        return resolve(request).stream(request.withStream(true));
    }

    public Mono<ModelListResponse> listModels() {
        ProviderClient provider = registry.get(ProviderIds.OPENAI);
        provider.ensureConfigured();
        return provider.listModels();
    }

    /**
     * Week 1 forwards every model name to OpenAI. YAML aliases and fallback chains
     * replace this method (ADR-0005).
     */
    private ProviderClient resolve(ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        return registry.get(ProviderIds.OPENAI);
    }
}
