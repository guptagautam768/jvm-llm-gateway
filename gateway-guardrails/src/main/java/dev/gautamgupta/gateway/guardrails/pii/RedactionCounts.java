package dev.gautamgupta.gateway.guardrails.pii;

import java.util.Map;

/** Renders {@code email=1 phone=2}. The values are occurrence counts, never matched text. */
public final class RedactionCounts {

    private RedactionCounts() {
    }

    public static String format(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        counts.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!builder.isEmpty()) {
                        builder.append(' ');
                    }
                    builder.append(entry.getKey()).append('=').append(entry.getValue());
                });
        return builder.isEmpty() ? "none" : builder.toString();
    }
}
