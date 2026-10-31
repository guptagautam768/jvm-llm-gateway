package dev.gautamgupta.gateway.tenancy;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.tenancy.budget.UsageEstimate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Budget check, post-call reconcile, and usage ledger for one metered request.
 * Auth and the request token bucket run in the web filter before this.
 */
public interface ChatTenancy {

    Mono<Void> preflight(ServerWebExchange exchange, ChatCompletionRequest request);

    /**
     * Same pre-flight as chat, for a route whose size is already an estimate.
     * Embeddings pass input strings and a completion estimate of zero.
     */
    Mono<Void> preflight(ServerWebExchange exchange, String route, String requestedModel, UsageEstimate estimate);

    Mono<Void> reconcile(ServerWebExchange exchange, String provider, String servedModel, Usage usage);

    void record(
            ServerWebExchange exchange,
            String route,
            String provider,
            String model,
            Usage usage,
            int status,
            String errorCode,
            long startNanos);

    String tenantId(ServerWebExchange exchange);
}
