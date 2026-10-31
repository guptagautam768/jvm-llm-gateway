package dev.gautamgupta.gateway.core.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EmbeddingServiceTest {

    @Test
    void aliasRewritesTheResponseModelAndSkipsProvidersThatCannotEmbed() {
        FakeEmbeddings anthropic = new FakeEmbeddings(ProviderIds.ANTHROPIC, false);
        FakeEmbeddings openai = new FakeEmbeddings(ProviderIds.OPENAI, true);
        EmbeddingService service = service(policy(route(
                "embed",
                target(ProviderIds.ANTHROPIC, "claude-haiku-4-5"),
                target(ProviderIds.OPENAI, "text-embedding-3-small"))), anthropic, openai);

        StepVerifier.create(service.embed(request("embed", "hello")))
                .assertNext(response -> {
                    assertThat(response.model()).isEqualTo("embed");
                    assertThat(response.data().get(0).get("embedding").get(0).asDouble()).isEqualTo(0.5d);
                })
                .verifyComplete();

        assertThat(anthropic.seen).isEmpty();
        assertThat(openai.seen).singleElement().extracting(EmbeddingRequest::model).isEqualTo("text-embedding-3-small");
    }

    @Test
    void upstream5xxFallsBackToTheNextEmbeddingProvider() {
        FakeEmbeddings openai = new FakeEmbeddings(ProviderIds.OPENAI, true);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 500, "{\"error\":{\"message\":\"down\"}}", null);
        FakeEmbeddings ollama = new FakeEmbeddings(ProviderIds.OLLAMA, true);
        EmbeddingService service = service(policy(route(
                "embed",
                target(ProviderIds.OPENAI, "text-embedding-3-small"),
                target(ProviderIds.OLLAMA, "nomic-embed-text"))), openai, ollama);

        StepVerifier.create(service.embed(request("embed", "hello")))
                .assertNext(response -> assertThat(response.model()).isEqualTo("embed"))
                .verifyComplete();

        assertThat(ollama.seen).hasSize(1);
    }

    @Test
    void client4xxDoesNotFallBack() {
        FakeEmbeddings openai = new FakeEmbeddings(ProviderIds.OPENAI, true);
        openai.failWith = ProviderException.upstream(ProviderIds.OPENAI, 400, "{\"error\":{\"message\":\"bad\"}}", null);
        FakeEmbeddings ollama = new FakeEmbeddings(ProviderIds.OLLAMA, true);
        EmbeddingService service = service(policy(route(
                "embed",
                target(ProviderIds.OPENAI, "text-embedding-3-small"),
                target(ProviderIds.OLLAMA, "nomic-embed-text"))), openai, ollama);

        StepVerifier.create(service.embed(request("embed", "hello")))
                .expectErrorSatisfies(error -> assertThat(((ProviderException) error).status()).isEqualTo(400))
                .verify();

        assertThat(ollama.seen).isEmpty();
    }

    @Test
    void noCapableTargetIsNotImplemented() {
        FakeEmbeddings anthropic = new FakeEmbeddings(ProviderIds.ANTHROPIC, false);
        EmbeddingService service = service(policy(route(
                "smart", target(ProviderIds.ANTHROPIC, "claude"))), anthropic);

        StepVerifier.create(service.embed(request("smart", "hello")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) error).kind()).isEqualTo(ProviderException.Kind.UNSUPPORTED);
                    assertThat(((ProviderException) error).status()).isEqualTo(501);
                })
                .verify();
    }

    @Test
    void missingInputIsRejectedBeforeAProviderCall() {
        FakeEmbeddings openai = new FakeEmbeddings(ProviderIds.OPENAI, true);
        EmbeddingService service = service(policy(route(
                "embed", target(ProviderIds.OPENAI, "text-embedding-3-small"))), openai);

        assertThatThrownBy(() -> service.validate(new EmbeddingRequest("embed", null)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("input");
        assertThat(openai.seen).isEmpty();
    }

    private static EmbeddingRequest request(String model, String input) {
        return new EmbeddingRequest(model, TextNode.valueOf(input));
    }

    private static EmbeddingService service(RoutingPolicy policy, FakeEmbeddings... providers) {
        return new EmbeddingService(
                new ProviderRegistry(List.of(providers)),
                new ModelRouter(policy),
                new ProviderCallGuard(policy.circuitBreaker()),
                null);
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

    private static final class FakeEmbeddings implements ProviderClient {
        private final String id;
        private final boolean embeddings;
        private final List<EmbeddingRequest> seen = new ArrayList<>();
        private ProviderException failWith;

        private FakeEmbeddings(String id, boolean embeddings) {
            this.id = id;
            this.embeddings = embeddings;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ProviderCapabilities capabilities() {
            return new ProviderCapabilities(true, true, embeddings);
        }

        @Override
        public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
            return Mono.empty();
        }

        @Override
        public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
            return Flux.empty();
        }

        @Override
        public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
            return Mono.defer(() -> {
                seen.add(request);
                if (failWith != null) {
                    return Mono.error(failWith);
                }
                String body = """
                        {"object":"embedding","index":0,"embedding":[0.5,0.25]}
                        """;
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                try {
                    return Mono.just(new EmbeddingResponse(
                            "list",
                            mapper.readTree("[" + body + "]"),
                            request.model(),
                            new Usage(1, 0, 1)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    return Mono.error(ex);
                }
            });
        }

        @Override
        public Mono<ModelListResponse> listModels() {
            return Mono.empty();
        }
    }
}
