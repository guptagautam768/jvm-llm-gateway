package dev.gautamgupta.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.core.provider.ProviderCapabilities;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayStartupChecksTest {

    @Test
    void shippedDefaultsAreValid() {
        assertThat(GatewayStartupChecks.problems(
                new OpenAiProviderProperties(),
                new AnthropicProviderProperties(),
                new OllamaProviderProperties(),
                new CacheProperties(),
                registry(true))).isEmpty();
    }

    @Test
    void aRelativeBaseUrlFailsStartup() {
        OllamaProviderProperties ollama = new OllamaProviderProperties();
        ollama.setBaseUrl("ollama:11434");

        assertThat(GatewayStartupChecks.problems(
                new OpenAiProviderProperties(),
                new AnthropicProviderProperties(),
                ollama,
                new CacheProperties(),
                registry(true)))
                .anyMatch(problem -> problem.contains("gateway.providers.ollama.base-url"));
    }

    @Test
    void anUnknownSemanticProviderFailsStartup() {
        CacheProperties cache = new CacheProperties();
        cache.getSemantic().setEmbeddingProvider("missing");

        assertThat(GatewayStartupChecks.problems(
                new OpenAiProviderProperties(),
                new AnthropicProviderProperties(),
                new OllamaProviderProperties(),
                cache,
                registry(true)))
                .anyMatch(problem -> problem.contains("missing"));
    }

    @Test
    void aProviderThatCannotEmbedCannotBackTheSemanticCache() {
        CacheProperties cache = new CacheProperties();
        cache.getSemantic().setEmbeddingProvider(ProviderIds.ANTHROPIC);

        assertThat(GatewayStartupChecks.problems(
                new OpenAiProviderProperties(),
                new AnthropicProviderProperties(),
                new OllamaProviderProperties(),
                cache,
                registry(false)))
                .anyMatch(problem -> problem.contains("cannot embed"));
    }

    private static ProviderRegistry registry(boolean anthropicEmbeds) {
        return new ProviderRegistry(List.of(
                client(ProviderIds.OPENAI, true),
                client(ProviderIds.ANTHROPIC, anthropicEmbeds),
                client(ProviderIds.OLLAMA, true)));
    }

    private static ProviderClient client(String id, boolean embeddings) {
        return new ProviderClient() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ProviderCapabilities capabilities() {
                return new ProviderCapabilities(true, true, embeddings);
            }

            @Override
            public reactor.core.publisher.Mono<dev.gautamgupta.gateway.api.chat.ChatCompletionResponse> complete(
                    dev.gautamgupta.gateway.api.chat.ChatCompletionRequest request) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Flux<dev.gautamgupta.gateway.api.chat.ChatCompletionChunk> stream(
                    dev.gautamgupta.gateway.api.chat.ChatCompletionRequest request) {
                return reactor.core.publisher.Flux.empty();
            }
        };
    }
}
