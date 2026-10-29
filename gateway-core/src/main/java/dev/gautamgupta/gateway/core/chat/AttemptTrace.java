package dev.gautamgupta.gateway.core.chat;

/** Provider that actually served, or the last one attempted when the call fails. */
public final class AttemptTrace {

    private volatile String providerId = "none";
    private volatile String model = "none";

    public void record(String providerId, String model) {
        this.providerId = providerId == null || providerId.isBlank() ? "none" : providerId;
        this.model = model == null || model.isBlank() ? "none" : model;
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }
}
