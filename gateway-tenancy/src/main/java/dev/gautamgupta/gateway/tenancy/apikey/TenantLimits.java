package dev.gautamgupta.gateway.tenancy.apikey;

import java.time.Duration;

/**
 * Per-tenant caps. Null budgets and a null tokens-per-minute bucket are unlimited.
 * A rate bucket is always present: capacity is the burst, refill is tokens per second.
 */
public record TenantLimits(
        int rateCapacity,
        double rateRefillPerSecond,
        Long tokensPerMinute,
        Long tokenBudget,
        Long costBudgetMicros,
        Duration budgetWindow
) {

    public TenantLimits {
        if (rateCapacity <= 0) {
            throw new IllegalArgumentException("rate capacity must be positive");
        }
        if (rateRefillPerSecond <= 0) {
            throw new IllegalArgumentException("rate refill must be positive");
        }
        if (budgetWindow == null || budgetWindow.isZero() || budgetWindow.isNegative()) {
            throw new IllegalArgumentException("budget window must be positive");
        }
    }

    public boolean hasTokenBudget() {
        return tokenBudget != null;
    }

    public boolean hasCostBudget() {
        return costBudgetMicros != null;
    }

    public boolean hasTokensPerMinute() {
        return tokensPerMinute != null && tokensPerMinute > 0;
    }
}
