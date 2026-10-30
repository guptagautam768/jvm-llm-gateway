package dev.gautamgupta.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.core.chat.AttemptTrace;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.ServerSentEvent;

@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);
    private static final String ROUTE = "chat.completions";

    private final ChatCompletionService service;
    private final ObjectMapper objectMapper;
    private final GatewayMetrics metrics;

    public ChatCompletionsController(
            ChatCompletionService service,
            ObjectMapper objectMapper,
            GatewayMetrics metrics
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object create(@RequestBody ChatCompletionRequest request, ServerHttpResponse response) {
        service.validate(request);
        boolean stream = Boolean.TRUE.equals(request.stream());
        log.info("chat completions model={} stream={}", request.model(), stream);
        if (stream) {
            response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            response.getHeaders().setCacheControl(CacheControl.noCache());
            response.getHeaders().add("X-Accel-Buffering", "no");
            return stream(request);
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return complete(request);
    }

    private Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        long start = System.nanoTime();
        AttemptTrace trace = new AttemptTrace();
        return service.complete(request, trace)
                .doOnNext(body -> metrics.recordSuccess(ROUTE, trace.providerId(), trace.model(), body.usage(), start))
                .doOnError(error -> metrics.recordError(ROUTE, trace.providerId(), trace.model(), error, start));
    }

    private Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        long start = System.nanoTime();
        AttemptTrace trace = new AttemptTrace();
        AtomicReference<Usage> usage = new AtomicReference<>();
        AtomicBoolean sent = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        return service.stream(request, trace)
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        usage.set(chunk.usage());
                    }
                })
                .map(this::toEvent)
                .doOnNext(ignored -> sent.set(true))
                .onErrorResume(error -> {
                    failed.set(true);
                    metrics.recordError(ROUTE, trace.providerId(), trace.model(), error, start);
                    if (!sent.get()) {
                        return Flux.error(error);
                    }
                    return Flux.just(errorEvent(error));
                })
                .concatWith(Flux.defer(() -> failed.get() ? Flux.empty() : Flux.just(doneEvent())))
                .doOnComplete(() -> {
                    if (!failed.get()) {
                        metrics.recordSuccess(ROUTE, trace.providerId(), trace.model(), usage.get(), start);
                    }
                });
    }

    private ServerSentEvent<String> toEvent(ChatCompletionChunk chunk) {
        try {
            return ServerSentEvent.<String>builder().data(objectMapper.writeValueAsString(chunk)).build();
        } catch (JsonProcessingException ex) {
            throw ProviderException.malformed("gateway", "could not encode stream chunk", ex);
        }
    }

    private ServerSentEvent<String> errorEvent(Throwable error) {
        String message = "The provider failed while streaming.";
        String code = "upstream_unavailable";
        if (error instanceof ProviderException providerException) {
            message = providerException.getMessage();
            code = switch (providerException.kind()) {
                case TIMEOUT -> "provider_timeout";
                case CIRCUIT_OPEN -> "circuit_open";
                case NOT_CONFIGURED -> "provider_not_configured";
                default -> "upstream_unavailable";
            };
        }
        String json = "{\"error\":{\"message\":\"" + escape(message)
                + "\",\"type\":\"server_error\",\"code\":\"" + code + "\"}}";
        return ServerSentEvent.<String>builder().data(json).build();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }
}
