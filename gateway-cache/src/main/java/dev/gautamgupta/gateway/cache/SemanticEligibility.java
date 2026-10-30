package dev.gautamgupta.gateway.cache;

/** Temperature gate for the semantic cache (ADR-0006). Exact cache does not use this. */
public final class SemanticEligibility {

    /** OpenAI's default when the client omits temperature. */
    public static final double OMITTED_TEMPERATURE = 1.0d;

    private SemanticEligibility() {
    }

    public static boolean allowed(Double temperature, double maxTemperature) {
        double effective = temperature == null ? OMITTED_TEMPERATURE : temperature;
        return effective <= maxTemperature;
    }
}
