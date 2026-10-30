package dev.gautamgupta.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.cache.CacheQuery;
import dev.gautamgupta.gateway.cache.CacheRead;
import dev.gautamgupta.gateway.cache.CachedCompletion;
import dev.gautamgupta.gateway.cache.ResponseCache;
import dev.gautamgupta.gateway.config.CacheProperties;
import dev.gautamgupta.gateway.core.routing.CircuitBreakerSettings;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.RetrySettings;
import dev.gautamgupta.gateway.core.routing.RouteCacheSettings;
import dev.gautamgupta.gateway.core.routing.RouteDefinition;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import dev.gautamgupta.gateway.core.routing.RoutingPolicy;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ChatCacheServiceTest {

    @Test
    void sizeLimitAndRedactionRunBeforeTheCacheSeesThePrompt() {
        List<String> order = new ArrayList<>();
        RecordingCache cache = new RecordingCache();
        cache.onRead = () -> order.add("cache");
        ChatCacheService service = service(
                RouteCacheSettings.inherit(),
                cache,
                request -> {
                    order.add("redact");
                    return request.withMessages(List.of(message("[EMAIL_1]")));
                },
                request -> order.add("size"));

        CacheDecision decision = service.lookup("acme", request("ada@example.com", 0.0), CacheMode.USE)
                .block(Duration.ofSeconds(2));

        assertThat(order).containsExactly("size", "redact", "cache");
        assertThat(decision.redacted().messages().get(0).content().asText()).isEqualTo("[EMAIL_1]");
        assertThat(cache.seen.request().messages().get(0).content().asText()).isEqualTo("[EMAIL_1]");
        assertThat(decision.header()).isEqualTo("miss");
        assertThat(cache.seen.semantic()).isTrue();
    }

    @Test
    void bypassStreamingAndADisabledCacheDoNotLookUp() {
        RecordingCache cache = new RecordingCache();
        ChatCacheService service = service(RouteCacheSettings.inherit(), cache, request -> request, request -> {
        });

        assertThat(service.lookup("acme", request("Hello", 0.0), CacheMode.BYPASS).block().header()).isEqualTo("bypass");
        assertThat(service.lookup("acme", request("Hello", 0.0), CacheMode.STREAM).block().header()).isEqualTo("skip");
        assertThat(cache.reads).isZero();

        CacheProperties off = new CacheProperties();
        off.setEnabled(false);
        ChatCacheService disabled = new ChatCacheService(off, router(RouteCacheSettings.inherit()), cache, request -> request, request -> {
        });
        CacheDecision skipped = disabled.lookup("acme", request("Hello", 0.0), CacheMode.USE).block();
        assertThat(skipped.header()).isNull();
        assertThat(skipped.redacted().messages().get(0).content().asText()).isEqualTo("Hello");
        assertThat(cache.reads).isZero();
    }

    @Test
    void semanticStaysOffAboveTheTemperatureCapOrWhenTheRouteDisablesIt() {
        RecordingCache cache = new RecordingCache();
        ChatCacheService service = service(RouteCacheSettings.inherit(), cache, request -> request, request -> {
        });

        service.lookup("acme", request("Hello", null), CacheMode.USE).block();
        assertThat(cache.seen.semantic()).isFalse();
        service.lookup("acme", request("Hello", 0.9), CacheMode.USE).block();
        assertThat(cache.seen.semantic()).isFalse();
        service.lookup("acme", request("Hello", 0.0), CacheMode.USE).block();
        assertThat(cache.seen.semantic()).isTrue();

        RecordingCache restricted = new RecordingCache();
        ChatCacheService quiet = service(new RouteCacheSettings(false, 0.99d), restricted, request -> request, request -> {
        });
        quiet.lookup("acme", request("Hello", 0.0), CacheMode.USE).block();
        assertThat(restricted.seen.semantic()).isFalse();
        assertThat(restricted.seen.threshold()).isEqualTo(0.99d);
    }

    @Test
    void storeSkipsHitsAndBypass() {
        RecordingCache cache = new RecordingCache();
        ChatCacheService service = service(RouteCacheSettings.inherit(), cache, request -> request, request -> {
        });
        CacheDecision miss = service.lookup("acme", request("Hello", 0.0), CacheMode.USE).block();
        service.store(miss, new CachedCompletion("openai", "gpt-4o-mini", null)).block();
        assertThat(cache.writes).isEqualTo(1);

        cache.next = CacheRead.exactHit(new CachedCompletion("openai", "gpt-4o-mini", null));
        CacheDecision hit = service.lookup("acme", request("Hello", 0.0), CacheMode.USE).block();
        service.store(hit, new CachedCompletion("openai", "gpt-4o-mini", null)).block();
        assertThat(hit.header()).isEqualTo("exact");
        assertThat(cache.writes).isEqualTo(1);

        CacheDecision bypass = service.lookup("acme", request("Hello", 0.0), CacheMode.BYPASS).block();
        service.store(bypass, new CachedCompletion("openai", "gpt-4o-mini", null)).block();
        assertThat(cache.writes).isEqualTo(1);
    }

    private static ChatCacheService service(
            RouteCacheSettings settings,
            RecordingCache cache,
            PromptRedactor redactor,
            RequestSizeLimit sizeLimit
    ) {
        return new ChatCacheService(new CacheProperties(), router(settings), cache, redactor, sizeLimit);
    }

    private static ModelRouter router(RouteCacheSettings settings) {
        RouteDefinition route = new RouteDefinition(
                "fast",
                List.of(new RouteTarget("openai", "gpt-4o-mini")),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                settings);
        return new ModelRouter(new RoutingPolicy(
                List.of(route),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                new CircuitBreakerSettings(50, 4, 2, Duration.ofSeconds(5), 1),
                1L));
    }

    private static ChatCompletionRequest request(String content, Double temperature) {
        return new ChatCompletionRequest(
                "fast",
                List.of(message(content)),
                temperature,
                null, null, null, null, null, 16, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ChatCompletionRequest.ChatMessage message(String content) {
        return new ChatCompletionRequest.ChatMessage("user", TextNode.valueOf(content), null, null, null);
    }

    private static final class RecordingCache implements ResponseCache {
        private int reads;
        private int writes;
        private CacheQuery seen;
        private CacheRead next = CacheRead.miss(List.of(), new float[] {1f, 0f, 0f, 0f});
        private Runnable onRead = () -> {
        };

        @Override
        public Mono<CacheRead> read(CacheQuery query) {
            reads++;
            seen = query;
            onRead.run();
            return Mono.just(next);
        }

        @Override
        public Mono<Void> write(CacheQuery query, CachedCompletion completion, float[] embedding) {
            writes++;
            return Mono.empty();
        }

        @Override
        public boolean semanticReady() {
            return true;
        }
    }
}
