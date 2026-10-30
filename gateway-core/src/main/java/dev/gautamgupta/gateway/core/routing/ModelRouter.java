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
                return new ResolvedRoute(
                        model, route.targets(), route.timeout(), route.retry(), true, route.cacheSettings());
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
            RouteTarget match = matches.get(0);
            return new ResolvedRoute(
                    model,
                    List.of(match),
                    policy.defaultTimeout(),
                    policy.defaultRetry(),
                    false,
                    cacheSettingsForPrimary(match));
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

    /**
     * A concrete model name is not an alias, so it inherits the cache settings of the
     * alias whose primary target is that model. Later aliases do not override the first.
     */
    private RouteCacheSettings cacheSettingsForPrimary(RouteTarget match) {
        for (RouteDefinition route : policy.routes()) {
            if (route.targets().isEmpty()) {
                continue;
            }
            RouteTarget primary = route.targets().get(0);
            if (primary.provider().equals(match.provider()) && primary.model().equals(match.model())) {
                return route.cacheSettings();
            }
        }
        return RouteCacheSettings.inherit();
    }
}
