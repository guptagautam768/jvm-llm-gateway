package dev.gautamgupta.gateway.core.routing;

import java.time.Duration;
import java.util.List;

/**
 * What one client {@code model} string resolved to.
 *
 * @param alias true when the client sent a route alias; false when they sent a concrete model
 */
public record ResolvedRoute(
        String requestedModel,
        List<RouteTarget> targets,
        Duration timeout,
        RetrySettings retry,
        boolean alias
) {

    public ResolvedRoute {
        targets = List.copyOf(targets);
    }
}
