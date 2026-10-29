package dev.gautamgupta.gateway.core.routing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.gautamgupta.gateway.core.provider.ProviderCapabilities;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingPolicyTest {

    @Test
    void unknownProviderFailsStartup() {
        RoutingPolicy policy = policy(new RouteDefinition(
                "local",
                List.of(new RouteTarget(ProviderIds.OLLAMA, "llama3.1")),
                Duration.ofSeconds(1),
                retry()));
        ProviderRegistry registry = new ProviderRegistry(List.of(client(ProviderIds.OPENAI, true)));

        assertThatThrownBy(() -> policy.validate(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
    }

    @Test
    void sameModelOnTwoProvidersFailsStartup() {
        RoutingPolicy policy = policy(new RouteDefinition(
                "fast",
                List.of(
                        new RouteTarget(ProviderIds.OPENAI, "shared"),
                        new RouteTarget(ProviderIds.ANTHROPIC, "shared")),
                Duration.ofSeconds(1),
                retry()));
        ProviderRegistry registry = new ProviderRegistry(List.of(
                client(ProviderIds.OPENAI, true),
                client(ProviderIds.ANTHROPIC, true)));

        assertThatThrownBy(() -> policy.validate(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared");
    }

    @Test
    void aProviderWithoutChatCannotBeATarget() {
        RoutingPolicy policy = policy(new RouteDefinition(
                "fast",
                List.of(new RouteTarget(ProviderIds.ANTHROPIC, "claude")),
                Duration.ofSeconds(1),
                retry()));
        ProviderRegistry registry = new ProviderRegistry(List.of(client(ProviderIds.ANTHROPIC, false)));

        assertThatThrownBy(() -> policy.validate(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot serve chat");
    }

    private static RoutingPolicy policy(RouteDefinition route) {
        return new RoutingPolicy(
                List.of(route),
                Duration.ofSeconds(1),
                retry(),
                new CircuitBreakerSettings(50, 4, 2, Duration.ofSeconds(5), 1),
                1L);
    }

    private static RetrySettings retry() {
        return new RetrySettings(1, Duration.ofMillis(1), 1);
    }

    private static ProviderClient client(String id, boolean chat) {
        return new ProviderClient() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ProviderCapabilities capabilities() {
                return new ProviderCapabilities(chat, chat, false);
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
