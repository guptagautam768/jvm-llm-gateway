package dev.gautamgupta.gateway.core.routing;

import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static routing table loaded at startup (ADR-0005). Changing it requires a restart. */
public record RoutingPolicy(
        List<RouteDefinition> routes,
        Duration defaultTimeout,
        RetrySettings defaultRetry,
        CircuitBreakerSettings circuitBreaker,
        long modelCatalogCreated
) {

    public RoutingPolicy {
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("default timeout must be positive");
        }
        if (defaultRetry == null) {
            throw new IllegalArgumentException("default retry settings are required");
        }
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("circuit breaker settings are required");
        }
    }

    public void validate(ProviderRegistry registry) {
        List<String> problems = new ArrayList<>();
        if (routes.isEmpty()) {
            problems.add("at least one gateway.routes alias is required");
        }
        Map<String, String> concreteOwners = new LinkedHashMap<>();
        for (RouteDefinition route : routes) {
            if (route.alias().isBlank()) {
                problems.add("a route alias is blank");
                continue;
            }
            if (route.targets().isEmpty()) {
                problems.add("route " + route.alias() + " has no targets");
            }
            for (RouteTarget target : route.targets()) {
                if (target.provider().isBlank() || target.model().isBlank()) {
                    problems.add("route " + route.alias() + " has a target with a blank provider or model");
                    continue;
                }
                String previous = concreteOwners.putIfAbsent(target.model(), target.provider());
                if (previous != null && !previous.equals(target.provider())) {
                    problems.add("model " + target.model() + " is configured for both " + previous + " and "
                            + target.provider() + "; give clients an alias instead");
                }
                try {
                    ProviderClient client = registry.get(target.provider());
                    if (!client.capabilities().chat() || !client.capabilities().stream()) {
                        problems.add("provider " + target.provider() + " cannot serve chat routes");
                    }
                } catch (IllegalArgumentException ex) {
                    problems.add("route " + route.alias() + " references unknown provider " + target.provider());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid gateway routing configuration: " + String.join("; ", problems));
        }
    }
}
