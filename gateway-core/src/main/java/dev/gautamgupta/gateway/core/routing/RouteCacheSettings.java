package dev.gautamgupta.gateway.core.routing;

/**
 * Per-alias cache overrides (ADR-0006). Null fields inherit the global
 * {@code gateway.cache} settings. A concrete model call uses the settings of the
 * alias whose primary target is that model.
 */
public record RouteCacheSettings(Boolean semanticEnabled, Double threshold) {

    public static RouteCacheSettings inherit() {
        return new RouteCacheSettings(null, null);
    }

    public RouteCacheSettings {
        if (threshold != null && (threshold.isNaN() || threshold < 0.0d || threshold > 1.0d)) {
            throw new IllegalArgumentException("route semantic threshold must be between 0 and 1");
        }
    }
}
