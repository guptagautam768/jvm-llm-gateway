package dev.gautamgupta.gateway.tenancy.error;

import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;

/** Stable auth, limit, and dependency failures (ADR-0007, API-030/031/034/036). */
public final class TenancyException extends RuntimeException {

    public enum Kind {
        INVALID_API_KEY(401, "invalid_api_key", "invalid_request_error", "Invalid API key."),
        TENANT_DISABLED(403, "tenant_disabled", "invalid_request_error", "Tenant is disabled."),
        RATE_LIMIT(429, "rate_limit_exceeded", "rate_limit_error", "Rate limit exceeded."),
        TOKEN_BUDGET(429, "token_budget_exceeded", "rate_limit_error", "Token budget exceeded."),
        COST_BUDGET(429, "cost_budget_exceeded", "rate_limit_error", "Cost budget exceeded."),
        DEPENDENCY(503, "dependency_unavailable", "server_error", "A required dependency is unavailable.");

        private final int httpStatus;
        private final String code;
        private final String type;
        private final String message;

        Kind(int httpStatus, String code, String type, String message) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.type = type;
            this.message = message;
        }
    }

    private final Kind kind;
    private final Long retryAfterSeconds;
    private final RateLimitDecision rateLimit;

    private TenancyException(Kind kind, Long retryAfterSeconds, RateLimitDecision rateLimit, Throwable cause) {
        super(kind.message, cause);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
        this.rateLimit = rateLimit;
    }

    public static TenancyException invalidApiKey() {
        return new TenancyException(Kind.INVALID_API_KEY, null, null, null);
    }

    public static TenancyException tenantDisabled() {
        return new TenancyException(Kind.TENANT_DISABLED, null, null, null);
    }

    public static TenancyException rateLimit(RateLimitDecision decision) {
        return new TenancyException(Kind.RATE_LIMIT, decision.retryAfterSeconds(), decision, null);
    }

    public static TenancyException tokenBudget(long retryAfterSeconds, RateLimitDecision rateLimit) {
        return new TenancyException(Kind.TOKEN_BUDGET, retryAfterSeconds, rateLimit, null);
    }

    public static TenancyException costBudget(long retryAfterSeconds, RateLimitDecision rateLimit) {
        return new TenancyException(Kind.COST_BUDGET, retryAfterSeconds, rateLimit, null);
    }

    public static TenancyException dependency(Throwable cause) {
        return new TenancyException(Kind.DEPENDENCY, null, null, cause);
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return kind.httpStatus;
    }

    public String code() {
        return kind.code;
    }

    public String type() {
        return kind.type;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public RateLimitDecision rateLimit() {
        return rateLimit;
    }
}
