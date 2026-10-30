package dev.gautamgupta.gateway.core.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRouterCacheTest {

    @Test
    void aliasKeepsItsCacheSettingsAndAConcretePrimaryInheritsThem() {
        RouteDefinition fast = new RouteDefinition(
                "fast",
                List.of(new RouteTarget("openai", "gpt-4o-mini"), new RouteTarget("anthropic", "claude-haiku-4-5")),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                new RouteCacheSettings(false, 0.99d));
        ModelRouter router = new ModelRouter(new RoutingPolicy(
                List.of(fast),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                new CircuitBreakerSettings(50, 4, 2, Duration.ofSeconds(5), 1),
                1L));

        assertThat(router.resolve("fast").cacheSettings().semanticEnabled()).isFalse();
        assertThat(router.resolve("fast").cacheSettings().threshold()).isEqualTo(0.99d);
        assertThat(router.resolve("gpt-4o-mini").cacheSettings().semanticEnabled()).isFalse();
        assertThat(router.resolve("claude-haiku-4-5").cacheSettings().semanticEnabled()).isNull();
        assertThat(router.resolve("claude-haiku-4-5").cacheSettings().threshold()).isNull();
    }
}
