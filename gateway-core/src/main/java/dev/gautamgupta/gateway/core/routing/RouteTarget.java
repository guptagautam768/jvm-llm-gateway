package dev.gautamgupta.gateway.core.routing;

/** One provider and the upstream model name it should be called with. */
public record RouteTarget(String provider, String model) {

    public RouteTarget {
        provider = provider == null ? "" : provider.trim();
        model = model == null ? "" : model.trim();
    }
}
