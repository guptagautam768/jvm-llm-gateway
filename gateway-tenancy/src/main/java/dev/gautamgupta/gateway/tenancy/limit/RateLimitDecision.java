package dev.gautamgupta.gateway.tenancy.limit;

/**
 * Result of one token-bucket take. {@code resetEpochSeconds} is when the bucket
 * is expected to be full again. {@code retryAfterSeconds} is zero when the take was allowed.
 */
public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterSeconds,
        long resetEpochSeconds
) {

    public static RateLimitDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty rate limit result");
        }
        String[] parts = raw.trim().split("\\|");
        if (parts.length != 5) {
            throw new IllegalArgumentException("unexpected rate limit result");
        }
        boolean allowed = "1".equals(parts[0]);
        long remaining = Long.parseLong(parts[1]);
        long retryMs = Long.parseLong(parts[2]);
        long limit = Long.parseLong(parts[3]);
        long resetMs = Long.parseLong(parts[4]);
        long retrySeconds = allowed || retryMs <= 0 ? 0 : Math.max(1, (retryMs + 999) / 1000);
        long resetSeconds = Math.max(0, resetMs / 1000);
        return new RateLimitDecision(allowed, limit, Math.max(0, remaining), retrySeconds, resetSeconds);
    }
}
