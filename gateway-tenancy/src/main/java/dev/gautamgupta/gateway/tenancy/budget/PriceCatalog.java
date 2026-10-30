package dev.gautamgupta.gateway.tenancy.budget;

import java.util.Map;
import java.util.Optional;

/** YAML price table (FR-071). A missing model yields an empty lookup, not a zero price. */
public final class PriceCatalog {

    private final Map<String, ModelPrice> prices;

    public PriceCatalog(Map<String, ModelPrice> prices) {
        this.prices = Map.copyOf(prices);
    }

    public Optional<ModelPrice> find(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(prices.get(model));
    }

    public Optional<Long> costMicros(String model, int promptTokens, int completionTokens) {
        return find(model).map(price -> price.micros(promptTokens, completionTokens));
    }
}
