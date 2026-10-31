package dev.gautamgupta.gateway.web;

import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.core.chat.AttemptTrace;
import dev.gautamgupta.gateway.core.embed.EmbeddingInputs;
import dev.gautamgupta.gateway.core.embed.EmbeddingService;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.guardrails.pii.PiiDetectedException;
import dev.gautamgupta.gateway.guardrails.pii.RequestTooLargeException;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.tenancy.ChatTenancy;
import dev.gautamgupta.gateway.tenancy.RateLimitHeaders;
import dev.gautamgupta.gateway.tenancy.TenancyExchange;
import dev.gautamgupta.gateway.tenancy.budget.TokenEstimator;
import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class EmbeddingsController {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingsController.class);
    static final String ROUTE = "embeddings";

    private final EmbeddingService service;
    private final GatewayMetrics metrics;
    private final ChatTenancy tenancy;
    private final TokenEstimator estimator;
    private final EmbeddingGuard guard;

    public EmbeddingsController(
            EmbeddingService service,
            GatewayMetrics metrics,
            ChatTenancy tenancy,
            TokenEstimator estimator,
            EmbeddingGuard guard
    ) {
        this.service = service;
        this.metrics = metrics;
        this.tenancy = tenancy;
        this.estimator = estimator;
        this.guard = guard;
    }

    @PostMapping(path = "/v1/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<EmbeddingResponse> create(@RequestBody EmbeddingRequest request, ServerWebExchange exchange) {
        service.validate(request);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        List<String> texts = EmbeddingInputs.texts(request.input());
        return tenancy.preflight(exchange, ROUTE, request.model(), estimator.estimateInputs(texts))
                .then(Mono.fromRunnable(() -> applyRateHeaders(exchange)))
                .then(Mono.deferContextual(context -> {
                    RequestCorrelation.logRequest(log, context, ROUTE, request.model(), false);
                    return embed(request, texts, exchange);
                }));
    }

    private Mono<EmbeddingResponse> embed(EmbeddingRequest request, List<String> texts, ServerWebExchange exchange) {
        long start = System.nanoTime();
        AttemptTrace trace = new AttemptTrace();
        return Mono.fromCallable(() -> guard.prepare(tenancy.tenantId(exchange), request, texts))
                .flatMap(redacted -> service.embed(redacted, trace)
                        .flatMap(body -> tenancy.reconcile(exchange, trace.providerId(), trace.model(), body.usage())
                                .thenReturn(body))
                        .doOnNext(body -> {
                            recordAttempts(trace);
                            metrics.recordSuccess(
                                    ROUTE, trace.providerId(), trace.model(), body.usage(), start, tenancy.tenantId(exchange));
                            tenancy.record(
                                    exchange, ROUTE, trace.providerId(), trace.model(), body.usage(), 200, null, start);
                        }))
                .onErrorResume(error -> finishError(exchange, trace, error, start).then(Mono.error(error)));
    }

    private Mono<Void> finishError(ServerWebExchange exchange, AttemptTrace trace, Throwable error, long start) {
        if (error instanceof PiiDetectedException || error instanceof RequestTooLargeException || error instanceof InvalidRequestException) {
            return Mono.empty();
        }
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
}
