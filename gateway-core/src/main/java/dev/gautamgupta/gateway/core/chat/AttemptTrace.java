package dev.gautamgupta.gateway.core.chat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Provider that actually served, or the last one attempted when the call fails. */
public final class AttemptTrace {

    private volatile String providerId = "none";
    private volatile String model = "none";
    private final List<ProviderError> errors = new CopyOnWriteArrayList<>();
    private final List<Fallback> fallbacks = new CopyOnWriteArrayList<>();

    public void record(String providerId, String model) {
        this.providerId = providerId == null || providerId.isBlank() ? "none" : providerId;
        this.model = model == null || model.isBlank() ? "none" : model;
    }

    public void noteProviderError(String provider, String kind) {
        errors.add(new ProviderError(label(provider), label(kind)));
    }

    public void noteFallback(String fromProvider, String toProvider) {
        fallbacks.add(new Fallback(label(fromProvider), label(toProvider)));
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }

    public List<ProviderError> errors() {
        return List.copyOf(errors);
    }

    public List<Fallback> fallbacks() {
        return List.copyOf(fallbacks);
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    public record ProviderError(String provider, String kind) {
    }

    public record Fallback(String fromProvider, String toProvider) {
    }
}
