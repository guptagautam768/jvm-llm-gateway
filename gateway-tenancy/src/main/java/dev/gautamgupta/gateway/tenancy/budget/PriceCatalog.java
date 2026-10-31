package dev.gautamgupta.gateway.tenancy.budget;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * YAML price table (FR-071). A missing model yields an empty lookup, not a zero price,
 * unless the serving provider is in the zero-cost set (Ollama, ADR-0015).
 * An explicit row always wins, including a non-zero price for that provider.
 */
public final class PriceCatalog {

    private final Map<String, ModelPrice> prices;
    private final Set<String> zeroCostProviders;

    public PriceCatalog(Map<String, ModelPrice> prices) {
        this(prices, Set.of());
    }

    public PriceCatalog(Map<String, ModelPrice> prices, Set<String> zeroCostProviders) {
        this.prices = Map.copyOf(prices);
        this.zeroCostProviders = zeroCostProviders == null ? Set.of() : Set.copyOf(zeroCostProviders);
    }

    public Optional<ModelPrice> find(String model) {
        return find(null, model);
    }

    public Optional<ModelPrice> find(String provider, String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        ModelPrice explicit = prices.get(model);
        if (explicit != null) {
            return Optional.of(explicit);
        }
        if (provider != null && zeroCostProviders.contains(provider)) {
            return Optional.of(ModelPrice.ZERO);
        }
        return Optional.empty();
    }

    public Optional<Long> costMicros(String model, int promptTokens, int completionTokens) {
        return costMicros(null, model, promptTokens, completionTokens);
    }

    public Optional<Long> costMicros(String provider, String model, int promptTokens, int completionTokens) {
        return find(provider, model).map(price -> price.micros(promptTokens, completionTokens));
    }
}
