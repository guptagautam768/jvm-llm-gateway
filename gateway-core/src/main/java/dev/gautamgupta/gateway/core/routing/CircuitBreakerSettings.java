package dev.gautamgupta.gateway.core.routing;

import java.time.Duration;

/** Count-based breaker shared by every route for one {@code (provider, model)} pair. */
public record CircuitBreakerSettings(
        float failureRateThreshold,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState
) {

    public CircuitBreakerSettings {
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException("circuit-breaker failure-rate-threshold must be in (0, 100]");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("circuit-breaker sliding-window-size must be at least 1");
        }
        if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
            throw new IllegalArgumentException(
                    "circuit-breaker minimum-number-of-calls must be between 1 and sliding-window-size");
        }
        if (waitDurationInOpenState == null || waitDurationInOpenState.isZero() || waitDurationInOpenState.isNegative()) {
            throw new IllegalArgumentException("circuit-breaker wait-duration-in-open-state must be positive");
        }
        if (permittedNumberOfCallsInHalfOpenState < 1) {
            throw new IllegalArgumentException(
                    "circuit-breaker permitted-number-of-calls-in-half-open-state must be at least 1");
        }
    }
}
