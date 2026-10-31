package dev.gautamgupta.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gautamgupta.gateway.api.error.ApiErrorResponse;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedger;
import dev.gautamgupta.gateway.persistence.ledger.UsageLedgerEntry;
import dev.gautamgupta.gateway.tenancy.RateLimitHeaders;
import dev.gautamgupta.gateway.tenancy.TenancyExchange;
import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord;
import dev.gautamgupta.gateway.tenancy.apikey.TenantAuthenticator;
import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import dev.gautamgupta.gateway.tenancy.limit.RequestLimiter;
import io.micrometer.observation.ObservationRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Auth and the per-tenant request bucket for chat completions and embeddings.
 * {@code Authorization: Bearer} is accepted, and so is the {@code api-key} header.
 * Redis failures on this path are 503 (fail-closed).
 */
public final class ChatTenancyWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ChatTenancyWebFilter.class);
    private static final String CHAT = "/v1/chat/completions";
    private static final String EMBEDDINGS = "/v1/embeddings";

    private final TenantAuthenticator authenticator;
    private final RequestLimiter limiter;
    private final UsageLedger ledger;
    private final GatewayMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;

    public ChatTenancyWebFilter(
            TenantAuthenticator authenticator,
            RequestLimiter limiter,
            UsageLedger ledger,
            GatewayMetrics metrics,
            ObjectMapper objectMapper,
            ObservationRegistry observations
    ) {
        this.authenticator = authenticator;
        this.limiter = limiter;
        this.ledger = ledger;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String route = meteredRoute(exchange);
        if (route == null) {
            return chain.filter(exchange);
        }
        exchange.getAttributes().put(TenancyExchange.STARTED, System.nanoTime());
        exchange.getAttributes().put(TenancyExchange.ROUTE, route);
        String presented = credential(exchange.getRequest());
        if (presented == null) {
            metrics.recordAccess(route, "401", null);
            return Spans.mono(observations, "gateway.auth", write(exchange, TenancyException.invalidApiKey()));
        }
        return Spans.mono(observations, "gateway.auth", authenticator.authenticate(presented)
                        .flatMap(record -> limiter.consume(
                                        "rl:" + record.tenantId(),
                                        record.limits().rateCapacity(),
                                        record.limits().rateRefillPerSecond(),
                                        1)
                                .map(decision -> new Authed(record, decision))))
                .flatMap(authed -> afterLimit(exchange, chain, route, authed.record(), authed.decision()))
                .onErrorResume(TenancyException.class, ex -> {
                    if (ex.kind() == TenancyException.Kind.DEPENDENCY) {
                        metrics.recordAccess(route, "503", null);
                        log.error("tenancy dependency failed");
                    } else if (ex.kind() == TenancyException.Kind.TENANT_DISABLED) {
                        metrics.recordAccess(route, "403", null);
                        log.warn("request rejected code=tenant_disabled route={}", route);
                    } else if (ex.kind() == TenancyException.Kind.INVALID_API_KEY) {
                        metrics.recordAccess(route, "401", null);
                        log.warn("request rejected code=invalid_api_key route={}", route);
                    }
                    return write(exchange, ex);
                });
    }

    private Mono<Void> afterLimit(
            ServerWebExchange exchange,
            WebFilterChain chain,
            String route,
            ApiKeyRecord record,
            dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision decision
    ) {
        exchange.getAttributes().put(TenancyExchange.PRINCIPAL, record);
        exchange.getAttributes().put(TenancyExchange.REQUEST_RATE, decision);
        if (!decision.allowed()) {
            metrics.recordRejection(route, "rate_limit_exceeded", "none", record.tenantId());
            recordLimit(exchange, route, record);
            log.warn("request rejected code=rate_limit_exceeded route={} tenant={}", route, record.tenantId());
            return write(exchange, TenancyException.rateLimit(decision));
        }
        return chain.filter(exchange);
    }

    private void recordLimit(ServerWebExchange exchange, String route, ApiKeyRecord record) {
        Long started = exchange.getAttribute(TenancyExchange.STARTED);
        long start = started == null ? System.nanoTime() : started;
        int latency = (int) Math.max(0, (System.nanoTime() - start) / 1_000_000L);
        String requestId = exchange.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER);
        ledger.record(new UsageLedgerEntry(
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                record.tenantId(),
                requestId == null || requestId.isBlank() ? "unknown" : requestId,
                route,
                null,
                null,
                null,
                null,
                null,
                "none",
                latency,
                429,
                "rate_limit_exceeded"));
    }

    private Mono<Void> write(ServerWebExchange exchange, TenancyException ex) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiErrorResponse.of(ex.getMessage(), ex.type(), ex.code(), null));
        } catch (JsonProcessingException encoding) {
            body = "{\"error\":{\"message\":\"Request failed.\",\"type\":\"server_error\",\"code\":\"internal_error\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(ex.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(body.length);
        if (ex.retryAfterSeconds() != null && ex.retryAfterSeconds() > 0) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        RateLimitHeaders.apply(response.getHeaders(), ex.rateLimit());
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    static String meteredRoute(ServerWebExchange exchange) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            return null;
        }
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (CHAT.equals(path)) {
            return "chat.completions";
        }
        if (EMBEDDINGS.equals(path)) {
            return "embeddings";
        }
        return null;
    }

    /**
     * Bearer is preferred. A non-Bearer {@code Authorization} value is rejected rather than
     * falling through to {@code api-key}. The api-key header is accepted when Authorization is absent.
     */
    static String credential(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = authorization.substring(7).trim();
                return token.isEmpty() ? null : token;
            }
            return null;
        }
        String apiKey = request.getHeaders().getFirst("api-key");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey.trim();
    }

    private record Authed(ApiKeyRecord record, dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision decision) {
    }
}
