package dev.gautamgupta.gateway.providers.ollama;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderCapabilities;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.providers.ndjson.NdjsonDecoder;
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
 * Ollama chat, streaming, and embeddings adapter.
 *
 * <p>ADR-0015: this client calls Ollama's native HTTP API with
 * {@link WebClient} and translates the JSON or NDJSON response into gateway DTOs.
 * Retries stay outside the call, with Resilience4j.
 */
public final class OllamaProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaProviderClient.class);

    private final WebClient webClient;
    private final OllamaClientSettings settings;
    private final ObjectMapper objectMapper;
    private final OllamaTranslator translator;

    public OllamaProviderClient(WebClient webClient, OllamaClientSettings settings, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.translator = new OllamaTranslator(objectMapper);
    }

    @Override
    public String id() {
        return ProviderIds.OLLAMA;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true);
    }

    @Override
    public void ensureConfigured() {
        if (settings.baseUrl().isBlank()) {
            throw ProviderException.notConfigured(id());
        }
    }

    @Override
    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        ensureConfigured();
        ObjectNode body = translator.chatBody(request, false);
        return webClient.post()
                .uri(settings.chatPath())
                .headers(this::authorization)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(this::readChat)
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        ensureConfigured();
        boolean includeUsage = request.streamOptions() != null
                && Boolean.TRUE.equals(request.streamOptions().includeUsage());
        ObjectNode body = translator.chatBody(request, true);
        return webClient.post()
                .uri(settings.chatPath())
                .headers(this::authorization)
                .accept(MediaType.APPLICATION_NDJSON)
                .bodyValue(body)
                .exchangeToFlux(response -> chunks(response, includeUsage))
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        ensureConfigured();
        ObjectNode body = translator.embedBody(request);
        return webClient.post()
                .uri(settings.embedPath())
                .headers(this::authorization)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(this::readEmbeddings)
                .onErrorMap(this::mapTransport);
    }

    private Mono<ChatCompletionResponse> readChat(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(upstream(response, body)));
        }
        if (log.isDebugEnabled() && response.request() != null) {
            log.debug("ollama {} {}", response.request().getMethod(), response.statusCode().value());
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(ProviderException.malformed(id(), "empty body", null)))
                .map(translator::chatResponse)
                .onErrorMap(JacksonException.class, ex -> ProviderException.malformed(id(), "response", ex));
    }

    private Mono<EmbeddingResponse> readEmbeddings(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(upstream(response, body)));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(ProviderException.malformed(id(), "empty body", null)))
                .map(translator::embeddings)
                .onErrorMap(JacksonException.class, ex -> ProviderException.malformed(id(), "embeddings", ex));
    }

    private Flux<ChatCompletionChunk> chunks(ClientResponse response, boolean includeUsage) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(upstream(response, body)));
        }
        return NdjsonDecoder.decode(response.bodyToFlux(DataBuffer.class))
                .concatMap(line -> chunksFromLine(line, includeUsage));
    }

    private Flux<ChatCompletionChunk> chunksFromLine(String line, boolean includeUsage) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if (node.hasNonNull("error")) {
                return Flux.error(ProviderException.upstream(id(), 502, translator.openAiError(line, 502), null));
            }
            return Flux.fromIterable(translator.chatChunks(node, includeUsage));
        } catch (JacksonException | IllegalArgumentException ex) {
            return Flux.error(ProviderException.malformed(id(), "stream chunk", ex));
        }
    }

    private ProviderException upstream(ClientResponse response, String body) {
        String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        int status = response.statusCode().value();
        return ProviderException.upstream(id(), status, translator.openAiError(body, status), retryAfter);
    }

    private void authorization(HttpHeaders headers) {
        if (settings.hasApiKey()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
        }
    }

    private Throwable mapTransport(Throwable error) {
        if (error instanceof ProviderException || error instanceof InvalidRequestException) {
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
}
