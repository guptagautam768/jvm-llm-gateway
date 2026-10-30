package dev.gautamgupta.gateway.providers.openai;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.provider.ProviderCapabilities;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.providers.sse.SseDataDecoder;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI chat and model-list adapter.
 *
 * <p>ADR-0012: this adapter calls the OpenAI REST API with {@link WebClient} so the
 * wire format survives the hop and WireMock can see it. ADR-0004's {@link ProviderClient}
 * port is unchanged. Anthropic and Ollama may still use Spring AI behind that port.
 * {@link #embed} calls {@code /v1/embeddings} for the semantic cache. The public
 * embeddings route is not exposed.
 */
public final class OpenAiProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProviderClient.class);

    private final WebClient webClient;
    private final OpenAiClientSettings settings;
    private final ObjectMapper objectMapper;

    public OpenAiProviderClient(WebClient webClient, OpenAiClientSettings settings, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return ProviderIds.OPENAI;
    }

    @Override
    public ProviderCapabilities capabilities() {
        // The adapter can embed. POST /v1/embeddings on the gateway is still not exposed.
        return new ProviderCapabilities(true, true, true);
    }

    @Override
    public void ensureConfigured() {
        if (settings.apiKey().isBlank()) {
            throw ProviderException.notConfigured(id());
        }
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        ensureConfigured();
        return webClient.post()
                .uri(settings.completionsPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request.withStream(null))
                .exchangeToMono(response -> read(response, ChatCompletionResponse.class))
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        ensureConfigured();
        return webClient.post()
                .uri(settings.completionsPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request.withStream(true))
                .exchangeToFlux(this::chunks)
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        ensureConfigured();
        return webClient.post()
                .uri(settings.embeddingsPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(response -> read(response, EmbeddingResponse.class))
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Mono<ModelListResponse> listModels() {
        ensureConfigured();
        return webClient.get()
                .uri(settings.modelsPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> read(response, ModelListResponse.class))
                .onErrorMap(this::mapTransport);
    }

    private Flux<ChatCompletionChunk> chunks(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(upstream(response, body)));
        }
        return SseDataDecoder.decode(response.bodyToFlux(DataBuffer.class))
                .concatMap(this::chunkFromData);
    }

    private Flux<ChatCompletionChunk> chunkFromData(String data) {
        String trimmed = data == null ? "" : data.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return Flux.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.hasNonNull("error")) {
                return Flux.error(ProviderException.upstream(id(), 502, trimmed, null));
            }
            return Flux.just(objectMapper.treeToValue(node, ChatCompletionChunk.class));
        } catch (JacksonException | IllegalArgumentException ex) {
            return Flux.error(ProviderException.malformed(id(), "stream chunk", ex));
        }
    }

    private <T> Mono<T> read(ClientResponse response, Class<T> type) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(upstream(response, body)));
        }
        if (log.isDebugEnabled() && response.request() != null) {
            log.debug("openai {} {}", response.request().getMethod(), response.statusCode().value());
        }
        return response.bodyToMono(type)
                .switchIfEmpty(Mono.error(ProviderException.malformed(id(), "empty body", null)));
    }

    private ProviderException upstream(ClientResponse response, String body) {
        String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        return ProviderException.upstream(id(), response.statusCode().value(), body, retryAfter);
    }

    private Throwable mapTransport(Throwable error) {
        if (error instanceof ProviderException) {
            return error;
        }
        if (isTimeout(error)) {
            return ProviderException.timeout(id(), error);
        }
        if (error instanceof WebClientRequestException) {
            return ProviderException.unavailable(id(), error);
        }
        return error;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TimeoutException) {
                return true;
            }
            String name = cursor.getClass().getName();
            if (name.endsWith("TimeoutException") || name.endsWith("ReadTimeoutException")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String bearer() {
        return "Bearer " + settings.apiKey();
    }
}
