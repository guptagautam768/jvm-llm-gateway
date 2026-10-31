package dev.gautamgupta.gateway.providers.anthropic;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
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
 * Anthropic Messages adapter.
 *
 * <p>The gateway edge speaks OpenAI chat completions. This client translates that
 * shape to the Anthropic Messages API and back, including SSE. ADR-0013 records why
 * this is a WebClient adapter rather than Spring AI. Anthropic does not embed.
 */
public final class AnthropicProviderClient implements ProviderClient {

    static final String API_KEY_HEADER = "x-api-key";
    static final String VERSION_HEADER = "anthropic-version";

    private static final Logger log = LoggerFactory.getLogger(AnthropicProviderClient.class);

    private final WebClient webClient;
    private final AnthropicClientSettings settings;
    private final ObjectMapper objectMapper;
    private final AnthropicMessageTranslator translator;

    public AnthropicProviderClient(WebClient webClient, AnthropicClientSettings settings, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.translator = new AnthropicMessageTranslator(objectMapper);
    }

    @Override
    public String id() {
        return ProviderIds.ANTHROPIC;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, false);
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
        ObjectNode body = translator.requestBody(request, settings.defaultMaxTokens(), false);
        return webClient.post()
                .uri(settings.messagesPath())
                .header(API_KEY_HEADER, settings.apiKey())
                .header(VERSION_HEADER, settings.version())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(this::readResponse)
                .onErrorMap(this::mapTransport);
    }

    @Override
    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        ensureConfigured();
        boolean includeUsage = request.streamOptions() != null && Boolean.TRUE.equals(request.streamOptions().includeUsage());
        ObjectNode body = translator.requestBody(request, settings.defaultMaxTokens(), true);
        return webClient.post()
                .uri(settings.messagesPath())
                .header(API_KEY_HEADER, settings.apiKey())
                .header(VERSION_HEADER, settings.version())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(response -> chunks(response, includeUsage))
                .onErrorMap(this::mapTransport);
    }

    private Mono<ChatCompletionResponse> readResponse(ClientResponse response) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(upstream(response, body)));
        }
        if (log.isDebugEnabled() && response.request() != null) {
            log.debug("anthropic {} {}", response.request().getMethod(), response.statusCode().value());
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(ProviderException.malformed(id(), "empty body", null)))
                .map(translator::response)
                .onErrorMap(JacksonException.class, ex -> ProviderException.malformed(id(), "response", ex));
    }

    private Flux<ChatCompletionChunk> chunks(ClientResponse response, boolean includeUsage) {
        if (response.statusCode().isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(upstream(response, body)));
        }
        return translator.chunks(SseDataDecoder.decode(response.bodyToFlux(DataBuffer.class)), includeUsage);
    }

    private ProviderException upstream(ClientResponse response, String body) {
        String retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        int status = response.statusCode().value();
        return ProviderException.upstream(id(), status, openAiError(body, status), retryAfter);
    }

    private String openAiError(String body, int status) {
        String message = "Anthropic returned HTTP " + status;
        String code = "upstream_error";
        try {
            if (body != null && !body.isBlank()) {
                JsonNode node = objectMapper.readTree(body);
                JsonNode error = node.get("error");
                if (error != null && error.hasNonNull("message")) {
                    message = error.get("message").asText();
                }
                if (error != null && error.hasNonNull("type")) {
                    code = error.get("type").asText();
                }
            }
        } catch (JacksonException ignored) {
            message = "Anthropic returned HTTP " + status;
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode error = root.putObject("error");
        error.put("message", message);
        error.put("type", status >= 500 || status == 429 ? "server_error" : "invalid_request_error");
        error.put("code", code);
        return root.toString();
    }

    private Throwable mapTransport(Throwable error) {
        if (error instanceof ProviderException || error instanceof dev.gautamgupta.gateway.core.provider.InvalidRequestException) {
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
