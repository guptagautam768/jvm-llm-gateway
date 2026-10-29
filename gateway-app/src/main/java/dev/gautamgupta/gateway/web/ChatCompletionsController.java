package dev.gautamgupta.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderIds;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
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
        return service.complete(request)
                .doOnNext(body -> metrics.recordSuccess(ROUTE, ProviderIds.OPENAI, request.model(), body.usage(), start))
                .doOnError(error -> metrics.recordError(ROUTE, ProviderIds.OPENAI, request.model(), error, start));
    }

    private Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        long start = System.nanoTime();
        AtomicReference<Usage> usage = new AtomicReference<>();
        return service.stream(request)
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) {
                        usage.set(chunk.usage());
                    }
                })
                .map(this::toEvent)
                .concatWith(Mono.fromSupplier(this::doneEvent))
                .doOnComplete(() -> metrics.recordSuccess(ROUTE, ProviderIds.OPENAI, request.model(), usage.get(), start))
                .doOnError(error -> metrics.recordError(ROUTE, ProviderIds.OPENAI, request.model(), error, start));
    }

    private ServerSentEvent<String> toEvent(ChatCompletionChunk chunk) {
        try {
            return ServerSentEvent.<String>builder().data(objectMapper.writeValueAsString(chunk)).build();
        } catch (JsonProcessingException ex) {
            throw ProviderException.malformed(ProviderIds.OPENAI, "could not encode stream chunk", ex);
        }
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }
}
