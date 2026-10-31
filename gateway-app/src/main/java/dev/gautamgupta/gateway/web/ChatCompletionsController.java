package dev.gautamgupta.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.error.ApiErrorResponse;
import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.cache.CacheBypass;
import dev.gautamgupta.gateway.cache.CacheObservation;
import dev.gautamgupta.gateway.cache.CachedCompletion;
import dev.gautamgupta.gateway.core.chat.AttemptTrace;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.tenancy.ChatTenancy;
import dev.gautamgupta.gateway.tenancy.RateLimitHeaders;
import dev.gautamgupta.gateway.tenancy.TenancyExchange;
import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;
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
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.codec.ServerSentEvent;

@RestController
public class ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionsController.class);
    private static final String ROUTE = "chat.completions";
    public static final String CACHE_HEADER = "X-Cache";

    private final ChatCompletionService service;
    private final ObjectMapper objectMapper;
    private final GatewayMetrics metrics;
    private final ChatTenancy tenancy;
    private final ChatCacheService cache;

    public ChatCompletionsController(
            ChatCompletionService service,
            ObjectMapper objectMapper,
            GatewayMetrics metrics,
            ChatTenancy tenancy,
            ChatCacheService cache
    ) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.tenancy = tenancy;
        this.cache = cache;
    }

    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object create(@RequestBody ChatCompletionRequest request, ServerWebExchange exchange) {
        service.validate(request);
        boolean stream = Boolean.TRUE.equals(request.stream());
        // Auth and the request bucket already ran. Pre-flight budget is next, then
        // size limit → redaction → cache → provider (ADR-0009). stream=true does not
        // read or write the cache. Redaction still runs on the prompt.
        ServerHttpResponse response = exchange.getResponse();
        if (stream) {
            response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            response.getHeaders().setCacheControl(CacheControl.noCache());
            response.getHeaders().add("X-Accel-Buffering", "no");
            return tenancy.preflight(exchange, request)
                    .then(Mono.fromRunnable(() -> applyRateHeaders(exchange)))
                    .thenMany(Flux.deferContextual(context -> {
                        RequestCorrelation.logChat(log, context, request.model(), true);
                        return cachedStream(request, exchange);
                    }));
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return tenancy.preflight(exchange, request)
                .then(Mono.fromRunnable(() -> applyRateHeaders(exchange)))
                .then(Mono.deferContextual(context -> {
                    RequestCorrelation.logChat(log, context, request.model(), false);
                    return cachedComplete(request, exchange);
                }));
    }

    private Flux<ServerSentEvent<String>> cachedStream(ChatCompletionRequest request, ServerWebExchange exchange) {
        return cache.lookup(tenancy.tenantId(exchange), request, CacheMode.STREAM)
                .flatMapMany(decision -> {
                    applyCacheHeader(exchange, decision);
                    return stream(decision.redacted(), exchange);
                });
    }

    private Mono<ChatCompletionResponse> cachedComplete(ChatCompletionRequest request, ServerWebExchange exchange) {
        long start = System.nanoTime();
        AttemptTrace trace = new AttemptTrace();
        CacheMode mode = CacheBypass.requested(exchange.getRequest().getHeaders().getFirst(CacheBypass.HEADER))
                ? CacheMode.BYPASS
                : CacheMode.USE;
        return cache.lookup(tenancy.tenantId(exchange), request, mode)
                .flatMap(decision -> {
                    applyCacheHeader(exchange, decision);
                    recordLookups(decision);
                    if (decision.hit() != null) {
                        CachedCompletion cached = decision.hit();
                        exchange.getAttributes().put(TenancyExchange.CACHE, decision.hitType());
                        exchange.getAttributes().put(TenancyExchange.CACHE_HIT, Boolean.TRUE);
                        return tenancy.reconcile(exchange, cached.provider(), cached.model(), cached.response().usage())
                                .thenReturn(cached.response())
                                .doOnNext(body -> {
                                    metrics.recordCacheHit(
                                            ROUTE, cached.provider(), cached.model(), body.usage(), tenancy.tenantId(exchange));
                                    tenancy.record(
                                            exchange, ROUTE, cached.provider(), cached.model(), body.usage(), 200, null, start);
                                });
                    }
                    exchange.getAttributes().put(TenancyExchange.CACHE, "none");
                    return service.complete(decision.redacted(), trace)
                            .flatMap(body -> cache.store(
                                            decision, new CachedCompletion(trace.providerId(), trace.model(), body))
                                    .then(tenancy.reconcile(exchange, trace.providerId(), trace.model(), body.usage()))
                                    .thenReturn(body))
                            .doOnNext(body -> {
                                recordAttempts(trace);
                                metrics.recordSuccess(
                                        ROUTE, trace.providerId(), trace.model(), body.usage(), start, tenancy.tenantId(exchange));
                                tenancy.record(
                                        exchange, ROUTE, trace.providerId(), trace.model(), body.usage(), 200, null, start);
                            })
                            .onErrorResume(error -> finishError(exchange, trace, error, start).then(Mono.error(error)));
                });
    }

    private Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request, ServerWebExchange exchange) {
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
                    return finishError(exchange, trace, error, start).thenMany(sent.get()
                            ? Flux.just(errorEvent(error))
                            : Flux.error(error));
                })
                .concatWith(Flux.defer(() -> {
                    if (failed.get()) {
                        return Flux.empty();
                    }
                    return tenancy.reconcile(exchange, trace.providerId(), trace.model(), usage.get())
                            .thenMany(Flux.just(doneEvent()));
                }))
                .doOnComplete(() -> {
                    if (!failed.get()) {
                        recordAttempts(trace);
                        metrics.recordSuccess(
                                ROUTE, trace.providerId(), trace.model(), usage.get(), start, tenancy.tenantId(exchange));
                        tenancy.record(exchange, ROUTE, trace.providerId(), trace.model(), usage.get(), 200, null, start);
                    }
                });
    }

    private Mono<Void> finishError(ServerWebExchange exchange, AttemptTrace trace, Throwable error, long start) {
        recordAttempts(trace);
        metrics.recordError(ROUTE, trace.providerId(), trace.model(), error, start, tenancy.tenantId(exchange));
        Outcome outcome = outcome(error);
        tenancy.record(exchange, ROUTE, trace.providerId(), trace.model(), null, outcome.status(), outcome.code(), start);
        return tenancy.reconcile(exchange, trace.providerId(), trace.model(), null);
    }

    private void recordAttempts(AttemptTrace trace) {
        for (AttemptTrace.ProviderError error : trace.errors()) {
            metrics.recordProviderError(error.provider(), error.kind());
        }
        for (AttemptTrace.Fallback fallback : trace.fallbacks()) {
            metrics.recordFallback(fallback.fromProvider(), fallback.toProvider());
        }
    }

    private void recordLookups(CacheDecision decision) {
        for (CacheObservation observation : decision.observations()) {
            metrics.recordCache(observation.result(), observation.type());
        }
    }

    private static void applyCacheHeader(ServerWebExchange exchange, CacheDecision decision) {
        if (decision.header() != null) {
            exchange.getResponse().getHeaders().set(CACHE_HEADER, decision.header());
        }
    }

    private static void applyRateHeaders(ServerWebExchange exchange) {
        RateLimitDecision decision = exchange.getAttribute(TenancyExchange.REQUEST_RATE);
        RateLimitHeaders.apply(exchange.getResponse().getHeaders(), decision);
    }

    private static Outcome outcome(Throwable error) {
        if (error instanceof ProviderException providerException) {
            String code = switch (providerException.kind()) {
                case NOT_CONFIGURED -> "provider_not_configured";
                case TIMEOUT -> "provider_timeout";
                case UNAVAILABLE -> "provider_unavailable";
                case MALFORMED -> "provider_malformed_response";
                case UNSUPPORTED -> "not_implemented";
                case CIRCUIT_OPEN -> "circuit_open";
                case UPSTREAM -> "upstream_error";
            };
            int status = providerException.status();
            if (status < 400 || status > 599) {
                status = 502;
            }
            return new Outcome(status, code);
        }
        return new Outcome(500, "internal_error");
    }

    private record Outcome(int status, String code) {
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
        try {
            String json = objectMapper.writeValueAsString(ApiErrorResponse.of(
                    message, "server_error", code, null));
            return ServerSentEvent.<String>builder().data(json).build();
        } catch (JsonProcessingException ex) {
            return ServerSentEvent.<String>builder()
                    .data("{\"error\":{\"message\":\"The provider failed while streaming.\",\"type\":\"server_error\",\"code\":\"upstream_unavailable\"}}")
                    .build();
        }
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder().data("[DONE]").build();
    }
}
