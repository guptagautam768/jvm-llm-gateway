package dev.gautamgupta.gateway.core.routing;

import java.time.Duration;

/**
 * Total attempts, including the first call. {@code maxAttempts} of 2 means one retry.
 * {@code backoff} is the first wait; later waits grow by {@code multiplier}.
 */
public record RetrySettings(int maxAttempts, Duration backoff, double multiplier) {

    public RetrySettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("retry max-attempts must be at least 1");
        }
        if (backoff == null || backoff.isZero() || backoff.isNegative()) {
            throw new IllegalArgumentException("retry backoff must be positive");
        }
        if (multiplier < 1.0d) {
            throw new IllegalArgumentException("retry multiplier must be at least 1");
        }
    }
}
