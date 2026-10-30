package dev.gautamgupta.gateway.core.routing;

import java.time.Duration;
import java.util.List;

/** One alias and its ordered targets (ADR-0005). The first target is the primary. */
public record RouteDefinition(
        String alias,
        List<RouteTarget> targets,
        Duration timeout,
        RetrySettings retry
) {

    public RouteDefinition {
        alias = alias == null ? "" : alias.trim();
        targets = targets == null ? List.of() : List.copyOf(targets);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("route timeout must be positive");
        }
        if (retry == null) {
            throw new IllegalArgumentException("route retry settings are required");
        }
    }
}
