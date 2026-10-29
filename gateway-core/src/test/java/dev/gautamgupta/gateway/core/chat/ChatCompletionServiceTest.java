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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatCompletionServiceTest {

    @Test
    void rejectsAMissingModelBeforeCallingTheProvider() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        ChatCompletionService service = service(provider);

        assertThatThrownBy(() -> service.validate(new ChatCompletionRequest(
                "  ",
                List.of(new ChatCompletionRequest.ChatMessage("user", null, null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("param")
                .isEqualTo("model");
        assertThat(provider.seen).isEmpty();
    }

    @Test
    void forwardsChatToOpenAiAndForcesTheStreamFlag() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        ChatCompletionService service = service(provider);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatCompletionRequest.ChatMessage("user", null, null, null, null)),
                0.2, null, null, true, null, null, 16, null, null, null, null, null, null, null, null, null, null, null);

        service.validate(request);
        StepVerifier.create(service.complete(request))
                .assertNext(response -> assertThat(response.id()).isEqualTo("chatcmpl-fake"))
                .verifyComplete();
        StepVerifier.create(service.stream(request))
                .assertNext(chunk -> assertThat(chunk.object()).isEqualTo("chat.completion.chunk"))
                .verifyComplete();

        assertThat(provider.seen).hasSize(2);
        assertThat(provider.seen.get(0).stream()).isNull();
        assertThat(provider.seen.get(1).stream()).isTrue();
    }

    @Test
    void missingApiKeyFailsBeforeTheCall() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        provider.configured = false;
        ChatCompletionService service = service(provider);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatCompletionRequest.ChatMessage("user", null, null, null, null)),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.validate(request))
                .isInstanceOf(ProviderException.class)
                .extracting("kind")
                .isEqualTo(ProviderException.Kind.NOT_CONFIGURED);
    }

    @Test
    void embeddingsAreUnsupportedUntilImplemented() {
        FakeProvider provider = new FakeProvider(ProviderIds.OPENAI);
        StepVerifier.create(provider.embed(new EmbeddingRequest("text-embedding-3-small", null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ProviderException.class);
                    assertThat(((ProviderException) error).kind()).isEqualTo(ProviderException.Kind.UNSUPPORTED);
                })
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

    private static ChatCompletionService service(FakeProvider provider) {
        return new ChatCompletionService(new ProviderRegistry(List.of(provider)));
    }

    private static final class FakeProvider implements ProviderClient {
        private final String id;
        private final List<ChatCompletionRequest> seen = new ArrayList<>();
        private boolean configured = true;
        private final AtomicBoolean called = new AtomicBoolean();

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
            seen.add(request);
            called.set(true);
            return Mono.just(new ChatCompletionResponse(
                    "chatcmpl-fake", "chat.completion", 1L, request.model(), List.of(), null, null));
        }

        @Override
        public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
            seen.add(request);
            return Flux.just(new ChatCompletionChunk(
                    "chatcmpl-fake",
                    "chat.completion.chunk",
                    1L,
                    request.model(),
                    List.of(new ChatCompletionChunk.Choice(0, new ChatCompletionChunk.Delta("assistant", null, null), "stop")),
                    null,
                    null));
        }

        @Override
        public Mono<ModelListResponse> listModels() {
            return Mono.just(new ModelListResponse("list", List.of()));
        }
    }
}
