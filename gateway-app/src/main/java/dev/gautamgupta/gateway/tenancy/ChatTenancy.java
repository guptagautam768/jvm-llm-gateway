package dev.gautamgupta.gateway.tenancy;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Budget check, post-call reconcile, and usage ledger for one chat request.
 * Auth and the request token bucket run in the web filter before this.
 */
public interface ChatTenancy {

    Mono<Void> preflight(ServerWebExchange exchange, ChatCompletionRequest request);

    Mono<Void> reconcile(ServerWebExchange exchange, String servedModel, Usage usage);

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
