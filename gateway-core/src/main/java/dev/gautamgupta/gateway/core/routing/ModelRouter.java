package dev.gautamgupta.gateway.core.routing;

import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a client {@code model} to an alias chain or to one concrete target.
 * Concrete names do not inherit another alias's fallback chain.
 */
public final class ModelRouter {

    private final RoutingPolicy policy;

    public ModelRouter(RoutingPolicy policy) {
        this.policy = policy;
    }

    public ResolvedRoute resolve(String model) {
        if (model == null || model.isBlank()) {
            throw new InvalidRequestException("model is required.", "model");
        }
        for (RouteDefinition route : policy.routes()) {
            if (route.alias().equals(model)) {
                return new ResolvedRoute(model, route.targets(), route.timeout(), route.retry(), true);
            }
        }
        List<RouteTarget> matches = new ArrayList<>();
        for (RouteDefinition route : policy.routes()) {
            for (RouteTarget target : route.targets()) {
                if (!target.model().equals(model)) {
                    continue;
                }
                boolean already = matches.stream().anyMatch(existing ->
                        existing.provider().equals(target.provider()) && existing.model().equals(target.model()));
                if (!already) {
                    matches.add(target);
                }
            }
        }
        if (matches.size() == 1) {
            return new ResolvedRoute(
                    model,
                    List.of(matches.get(0)),
                    policy.defaultTimeout(),
                    policy.defaultRetry(),
                    false);
        }
        if (matches.size() > 1) {
            throw new InvalidRequestException(
                    "model is configured on more than one provider; request an alias.",
                    "model");
        }
        throw new InvalidRequestException("Unknown model.", "model");
    }

    public ModelListResponse catalog() {
        Map<String, ModelListResponse.ModelCard> cards = new LinkedHashMap<>();
        long created = policy.modelCatalogCreated();
        for (RouteDefinition route : policy.routes()) {
            String owner = route.targets().isEmpty() ? "gateway" : route.targets().get(0).provider();
            cards.put(route.alias(), new ModelListResponse.ModelCard(route.alias(), "model", created, owner));
        }
        for (RouteDefinition route : policy.routes()) {
            for (RouteTarget target : route.targets()) {
                cards.putIfAbsent(
                        target.model(),
                        new ModelListResponse.ModelCard(target.model(), "model", created, target.provider()));
            }
        }
        return new ModelListResponse("list", List.copyOf(cards.values()));
    }
}
