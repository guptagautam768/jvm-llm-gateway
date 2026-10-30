package dev.gautamgupta.gateway.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderCapabilities;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import dev.gautamgupta.gateway.core.routing.CircuitBreakerSettings;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ProviderCallGuard;
import dev.gautamgupta.gateway.core.routing.RetrySettings;
import dev.gautamgupta.gateway.core.routing.RouteDefinition;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import dev.gautamgupta.gateway.core.routing.RoutingPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatCompletionServiceTest {

    @Test
    void rejectsAMissingModelBeforeCallingTheProvider() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        ChatCompletionService service = service(policy(route("gpt-4o-mini", target(ProviderIds.OPENAI, "gpt-4o-mini"))), provider);

        assertThatThrownBy(() -> service.validate(request("  ")))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ex -> ((InvalidRequestException) ex).param())
                .isEqualTo("model");
        assertThat(provider.seen).isEmpty();
    }

    @Test
    void aliasRewritesTheUpstreamModelAndEchoesTheAlias() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        RoutingPolicy policy = policy(route(
                "fast",
                target(ProviderIds.OPENAI, "gpt-4o-mini"),
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")));
        ChatCompletionService service = service(policy, openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .assertNext(response -> assertThat(response.model()).isEqualTo("fast"))
                .verifyComplete();

        assertThat(openai.seen).singleElement().extracting(ChatCompletionRequest::model).isEqualTo("gpt-4o-mini");
        assertThat(openai.seen.get(0).stream()).isNull();
        assertThat(anthropic.seen).isEmpty();
    }

    @Test
    void unknownModelIsRejected() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        ChatCompletionService service = service(policy(route("fast", target(ProviderIds.OPENAI, "gpt-4o-mini"))), provider);

        assertThatThrownBy(() -> service.validate(request("missing")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Unknown model");
        assertThat(provider.seen).isEmpty();
    }

    @Test
    void concreteModelDoesNotUseTheAliasFallbackChain() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 500, "{\"error\":{\"message\":\"down\"}}", null);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        RoutingPolicy policy = policy(route(
                "fast",
                target(ProviderIds.OPENAI, "gpt-4o-mini"),
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")));
        ChatCompletionService service = service(policy, openai, anthropic);

        StepVerifier.create(service.complete(request("gpt-4o-mini")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) error).status()).isEqualTo(502);
                    assertThat(((ProviderException) error).responseBody()).contains("upstream_unavailable");
                })
                .verify();
        assertThat(anthropic.seen).isEmpty();
    }

    @Test
    void retriesARetryableFailureThenFallsBack() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failuresRemaining = 2;
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        RouteDefinition fast = new RouteDefinition(
                "fast",
                List.of(target(ProviderIds.OPENAI, "gpt-4o-mini"), target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")),
                Duration.ofSeconds(2),
                new RetrySettings(2, Duration.ofMillis(1), 1));
        ChatCompletionService service = service(policy(fast), openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .assertNext(response -> assertThat(response.id()).isEqualTo("chatcmpl-" + ProviderIds.ANTHROPIC))
                .verifyComplete();

        assertThat(openai.seen).hasSize(2);
        assertThat(anthropic.seen).hasSize(1);
        assertThat(anthropic.seen.get(0).model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void clientErrorsDoNotRetryOrFallBack() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.upstream(
                ProviderIds.OPENAI, 400, "{\"error\":{\"message\":\"bad\",\"type\":\"invalid_request_error\",\"code\":\"bad\"}}", null);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        RouteDefinition fast = new RouteDefinition(
                "fast",
                List.of(target(ProviderIds.OPENAI, "gpt-4o-mini"), target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")),
                Duration.ofSeconds(2),
                new RetrySettings(3, Duration.ofMillis(1), 1));
        ChatCompletionService service = service(policy(fast), openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .expectErrorSatisfies(error -> assertThat(((ProviderException) error).status()).isEqualTo(400))
                .verify();
        assertThat(openai.seen).hasSize(1);
        assertThat(anthropic.seen).isEmpty();
    }

    @Test
    void rateLimitFallsBackAfterRetries() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 429, "{\"error\":{\"message\":\"slow\"}}", "2");
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        RouteDefinition fast = new RouteDefinition(
                "fast",
                List.of(target(ProviderIds.OPENAI, "gpt-4o-mini"), target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")),
                Duration.ofSeconds(2),
                new RetrySettings(2, Duration.ofMillis(1), 1));
        ChatCompletionService service = service(policy(fast), openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .assertNext(response -> assertThat(response.model()).isEqualTo("fast"))
                .verifyComplete();
        assertThat(openai.seen).hasSize(2);
        assertThat(anthropic.seen).hasSize(1);
    }

    @Test
    void timeoutFallsBackToTheNextTarget() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.timeout(ProviderIds.OPENAI, new java.util.concurrent.TimeoutException("slow"));
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        ChatCompletionService service = service(policy(route(
                "fast",
                target(ProviderIds.OPENAI, "gpt-4o-mini"),
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5"))), openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .assertNext(response -> assertThat(response.model()).isEqualTo("fast"))
                .verifyComplete();
        assertThat(anthropic.seen).hasSize(1);
    }

    @Test
    void exhaustedTimeoutStays504() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.timeout(ProviderIds.OPENAI, null);
        ChatCompletionService service = service(
                policy(route("fast", target(ProviderIds.OPENAI, "gpt-4o-mini"))),
                openai);

        StepVerifier.create(service.complete(request("fast")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) error).status()).isEqualTo(504);
                    assertThat(((ProviderException) error).kind()).isEqualTo(ProviderException.Kind.TIMEOUT);
                })
                .verify();
    }

    @Test
    void circuitOpensAndTheNextCallSkipsThePrimary() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 503, "{\"error\":{\"message\":\"down\"}}", null);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        CircuitBreakerSettings breaker = new CircuitBreakerSettings(50, 2, 2, Duration.ofMinutes(5), 1);
        RoutingPolicy policy = new RoutingPolicy(
                List.of(route(
                        "fast",
                        target(ProviderIds.OPENAI, "gpt-4o-mini"),
                        target(ProviderIds.ANTHROPIC, "claude-haiku-4-5"))),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                breaker,
                1L);
        ProviderCallGuard guard = new ProviderCallGuard(breaker);
        ChatCompletionService service = new ChatCompletionService(
                new ProviderRegistry(List.of(openai, anthropic)),
                new ModelRouter(policy),
                guard);

        StepVerifier.create(service.complete(request("fast"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.complete(request("fast"))).expectNextCount(1).verifyComplete();
        int callsBeforeOpen = openai.seen.size();
        StepVerifier.create(service.complete(request("fast"))).expectNextCount(1).verifyComplete();

        assertThat(callsBeforeOpen).isEqualTo(2);
        assertThat(openai.seen).hasSize(2);
        assertThat(guard.circuitBreakers().circuitBreaker("openai:gpt-4o-mini").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(anthropic.seen).hasSize(3);
    }

    @Test
    void circuitClosesAgainAfterTheWait() throws InterruptedException {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failuresRemaining = 2;
        CircuitBreakerSettings breaker = new CircuitBreakerSettings(50, 2, 2, Duration.ofMillis(150), 1);
        RoutingPolicy policy = new RoutingPolicy(
                List.of(route("only", target(ProviderIds.OPENAI, "gpt-4o-mini"))),
                Duration.ofSeconds(2),
                new RetrySettings(1, Duration.ofMillis(1), 1),
                breaker,
                1L);
        ProviderCallGuard guard = new ProviderCallGuard(breaker);
        ChatCompletionService service = new ChatCompletionService(
                new ProviderRegistry(List.of(openai)),
                new ModelRouter(policy),
                guard);

        StepVerifier.create(service.complete(request("only"))).expectError().verify();
        StepVerifier.create(service.complete(request("only"))).expectError().verify();
        assertThat(guard.circuitBreakers().circuitBreaker("openai:gpt-4o-mini").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(200);
        StepVerifier.create(service.complete(request("only")))
                .assertNext(response -> assertThat(response.model()).isEqualTo("only"))
                .verifyComplete();
        assertThat(guard.circuitBreakers().circuitBreaker("openai:gpt-4o-mini").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void streamFallsBackBeforeTheFirstChunkAndNotAfter() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failStream = true;
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        ChatCompletionService service = service(policy(route(
                "fast",
                target(ProviderIds.OPENAI, "gpt-4o-mini"),
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5"))), openai, anthropic);

        StepVerifier.create(service.stream(request("fast")))
                .assertNext(chunk -> assertThat(chunk.model()).isEqualTo("fast"))
                .verifyComplete();
        assertThat(anthropic.streams).isEqualTo(1);

        openai.failStream = false;
        openai.failAfterChunk = true;
        anthropic.streams = 0;
        StepVerifier.create(service.stream(request("fast")))
                .expectNextCount(1)
                .expectError(ProviderException.class)
                .verify();
        assertThat(anthropic.streams).isZero();
    }

    @Test
    void modelCatalogListsAliasesThenConcreteModels() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        ChatCompletionService service = service(policy(
                route("fast", target(ProviderIds.OPENAI, "gpt-4o-mini"), target(ProviderIds.ANTHROPIC, "claude-haiku-4-5")),
                route("smart", target(ProviderIds.OPENAI, "gpt-4o"))), openai, anthropic);

        ModelListResponse catalog = service.listModels().block();
        assertThat(catalog.object()).isEqualTo("list");
        assertThat(catalog.data()).extracting(ModelListResponse.ModelCard::id)
                .containsExactly("fast", "smart", "gpt-4o-mini", "claude-haiku-4-5", "gpt-4o");
        assertThat(catalog.data().get(0).ownedBy()).isEqualTo("openai");
        assertThat(catalog.data().get(3).ownedBy()).isEqualTo("anthropic");
    }

    @Test
    void missingApiKeyFailsTheCall() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        provider.configured = false;
        ChatCompletionService service = service(policy(route("gpt-4o-mini", target(ProviderIds.OPENAI, "gpt-4o-mini"))), provider);

        StepVerifier.create(service.complete(request("gpt-4o-mini")))
                .expectErrorSatisfies(error -> assertThat(((ProviderException) error).kind())
                        .isEqualTo(ProviderException.Kind.NOT_CONFIGURED))
                .verify();
    }

    @Test
    void missingFallbackKeyDoesNotHideAnUpstreamFailure() {
        FakeProvider openai = new FakeProvider(ProviderIds.OPENAI);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 500, null, null);
        FakeProvider anthropic = new FakeProvider(ProviderIds.ANTHROPIC);
        anthropic.configured = false;
        ChatCompletionService service = service(policy(route(
                "fast",
                target(ProviderIds.OPENAI, "gpt-4o-mini"),
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5"))), openai, anthropic);

        StepVerifier.create(service.complete(request("fast")))
                .expectErrorSatisfies(error -> assertThat(((ProviderException) error).status()).isEqualTo(502))
                .verify();
    }

    @Test
    void forwardsChatAndForcesTheStreamFlag() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        ChatCompletionService service = service(policy(route("gpt-4o-mini", target(ProviderIds.OPENAI, "gpt-4o-mini"))), provider);
        ChatCompletionRequest streaming = request("gpt-4o-mini").withStream(true);

        service.validate(streaming);
        StepVerifier.create(service.complete(streaming))
                .assertNext(response -> assertThat(response.id()).isEqualTo("chatcmpl-openai"))
                .verifyComplete();
        StepVerifier.create(service.stream(streaming))
                .assertNext(chunk -> assertThat(chunk.object()).isEqualTo("chat.completion.chunk"))
                .verifyComplete();

        assertThat(provider.seen).hasSize(2);
        assertThat(provider.seen.get(0).stream()).isNull();
        assertThat(provider.seen.get(1).stream()).isTrue();
    }

    @Test
    void embeddingsAreUnsupportedUntilImplemented() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        StepVerifier.create(provider.embed(new EmbeddingRequest("text-embedding-3-small", null)))
                .expectErrorSatisfies(error -> assertThat(((ProviderException) error).kind())
                        .isEqualTo(ProviderException.Kind.UNSUPPORTED))
                .verify();
    }

    @Test
    void duplicateProviderIdsFailFast() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(
                new FakeProvider(ProviderIds.OPENAI),
                new FakeProvider(ProviderIds.OPENAI))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void providerIdsStayDistinctForLaterRouting() {
        assertThat(List.of(ProviderIds.OPENAI, ProviderIds.ANTHROPIC, ProviderIds.OLLAMA))
                .doesNotHaveDuplicates()
                .containsExactly("openai", "anthropic", "ollama");
    }

    private static ChatCompletionRequest request(String model) {
        return new ChatCompletionRequest(
                model,
                List.of(new ChatCompletionRequest.ChatMessage("user", null, null, null, null)),
                0.2, null, null, null, null, null, 16, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static ChatCompletionService service(RoutingPolicy policy, FakeProvider... providers) {
        return new ChatCompletionService(
                new ProviderRegistry(List.of(providers)),
                new ModelRouter(policy),
                new ProviderCallGuard(policy.circuitBreaker()));
    }

    private static RoutingPolicy policy(RouteDefinition... routes) {
        RetrySettings retry = new RetrySettings(1, Duration.ofMillis(1), 1);
        return new RoutingPolicy(
                List.of(routes),
                Duration.ofSeconds(2),
                retry,
                new CircuitBreakerSettings(50, 20, 10, Duration.ofSeconds(30), 2),
                1L);
    }

    private static RouteDefinition route(String alias, RouteTarget... targets) {
        return new RouteDefinition(alias, List.of(targets), Duration.ofSeconds(2), new RetrySettings(1, Duration.ofMillis(1), 1));
    }

    private static RouteTarget target(String provider, String model) {
        return new RouteTarget(provider, model);
    }

    private static final class FakeProvider implements ProviderClient {
        private final String id;
        private final List<ChatCompletionRequest> seen = new ArrayList<>();
        private boolean configured = true;
        private int failuresRemaining;
        private ProviderException failWith;
        private boolean failStream;
        private boolean failAfterChunk;
        private int streams;

        private FakeProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(true, true, false);
        }

        @Override
        public void ensureConfigured() {
            if (!configured) {
                throw ProviderException.notConfigured(id);
            }
        }

        @Override
        public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
            return Mono.defer(() -> {
                seen.add(request);
                if (failWith != null) {
                    return Mono.error(failWith);
                }
                if (failuresRemaining > 0) {
                    failuresRemaining--;
                    return Mono.error(ProviderException.upstream(id, 500, "{\"error\":{\"message\":\"down\"}}", null));
                }
                return Mono.just(new ChatCompletionResponse(
                        "chatcmpl-" + id, "chat.completion", 1L, request.model(), List.of(), null, null));
            });
        }

        @Override
        public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
            return Flux.defer(() -> {
                streams++;
                seen.add(request);
                ChatCompletionChunk chunk = new ChatCompletionChunk(
                        "chatcmpl-" + id,
                        "chat.completion.chunk",
                        1L,
                        request.model(),
                        List.of(new ChatCompletionChunk.Choice(
                                0, new ChatCompletionChunk.Delta("assistant", null, null), "stop")),
                        null,
                        null);
                if (failStream) {
                    return Flux.error(ProviderException.upstream(id, 500, null, null));
                }
                if (failAfterChunk) {
                    return Flux.concat(Flux.just(chunk), Flux.error(ProviderException.upstream(id, 500, null, null)));
                }
                return Flux.just(chunk);
            });
        }

        @Override
        public Mono<ModelListResponse> listModels() {
            return Mono.just(new ModelListResponse("list", List.of()));
        }
    }
}
