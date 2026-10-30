package dev.gautamgupta.gateway.tenancy;

import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;
import org.springframework.http.HttpHeaders;

public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";

    private RateLimitHeaders() {
    }

    public static void apply(HttpHeaders headers, RateLimitDecision decision) {
        if (decision == null) {
            return;
        }
        headers.set(LIMIT, Long.toString(decision.limit()));
        headers.set(REMAINING, Long.toString(decision.remaining()));
        headers.set(RESET, Long.toString(decision.resetEpochSeconds()));
    }
}
