package dev.gautamgupta.gateway.guardrails.pii;

import java.util.Map;

/** Reject mode (ADR-0009). HTTP 400 {@code pii_detected}. The message has no matched text. */
public final class PiiDetectedException extends RuntimeException {

    private final Map<String, Integer> counts;

    public PiiDetectedException(Map<String, Integer> counts) {
        super("The request was rejected because it contains personal data.");
        this.counts = counts == null || counts.isEmpty() ? Map.of() : Map.copyOf(counts);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    public String code() {
        return "pii_detected";
    }
}
