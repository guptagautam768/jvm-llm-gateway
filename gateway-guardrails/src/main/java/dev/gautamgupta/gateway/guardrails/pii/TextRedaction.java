package dev.gautamgupta.gateway.guardrails.pii;

import java.util.List;
import java.util.Map;

/**
 * Redacted strings plus occurrence counts by metric type.
 * Counts never contain the matched text.
 */
public record TextRedaction(List<String> texts, Map<String, Integer> counts, boolean rejected) {

    public TextRedaction {
        texts = texts == null ? List.of() : List.copyOf(texts);
        counts = counts == null || counts.isEmpty() ? Map.of() : Map.copyOf(counts);
    }

    public static TextRedaction unchanged(List<String> texts) {
        return new TextRedaction(texts, Map.of(), false);
    }
}
