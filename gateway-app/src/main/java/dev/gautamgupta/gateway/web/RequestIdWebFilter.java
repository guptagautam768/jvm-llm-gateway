package dev.gautamgupta.gateway.web;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = sanitize(exchange.getRequest().getHeaders().getFirst(HEADER));
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(exchange).contextWrite(Context.of("requestId", requestId));
    }

    static String sanitize(String incoming) {
        if (incoming == null || !isSafe(incoming)) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }

    private static boolean isSafe(String value) {
        if (value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
