package dev.gautamgupta.gateway.tenancy.budget;

/** Pre-flight token estimate: prompt characters plus the reserved completion length. */
public record UsageEstimate(int promptTokens, int completionTokens) {

    public int total() {
        return promptTokens + completionTokens;
    }
}
