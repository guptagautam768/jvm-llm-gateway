package dev.gautamgupta.gateway.tenancy;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Used when {@code gateway.tenancy.enabled} is false. Chat stays open, as in weeks 1 and 2. */
public final class NoopChatTenancy implements ChatTenancy {

    @Override
    public Mono<Void> preflight(ServerWebExchange exchange, ChatCompletionRequest request) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> reconcile(ServerWebExchange exchange, String servedModel, Usage usage) {
        return Mono.empty();
    }

    @Override
    public void record(
            ServerWebExchange exchange,
            String route,
            String provider,
            String model,
            Usage usage,
            int status,
            String errorCode,
            long startNanos
    ) {
    }

    @Override
    public String tenantId(ServerWebExchange exchange) {
        return null;
    }
}
