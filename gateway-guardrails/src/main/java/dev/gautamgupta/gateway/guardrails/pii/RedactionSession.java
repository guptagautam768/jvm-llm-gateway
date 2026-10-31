package dev.gautamgupta.gateway.guardrails.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies every detector to one string, drops overlaps, and assigns placeholders.
 * The same type and the same matched text share one placeholder for the whole request.
 * Each kept span increments the occurrence count.
 */
final class RedactionSession {

    private final List<PiiDetector> detectors;
    private final Map<String, String> placeholders = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<PiiEntityType, Integer> nextIndex = new EnumMap<>(PiiEntityType.class);

    RedactionSession(List<PiiDetector> detectors) {
        this.detectors = detectors;
    }

    String rewrite(String text) {
        if (text == null || text.isEmpty() || detectors.isEmpty()) {
            return text;
        }
        List<PiiSpan> found = new ArrayList<>();
        for (PiiDetector detector : detectors) {
            found.addAll(detector.detect(text));
        }
        if (found.isEmpty()) {
            return text;
        }
        List<PiiSpan> chosen = resolve(found);
        if (chosen.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        int cursor = 0;
        for (PiiSpan span : chosen) {
            builder.append(text, cursor, span.start());
            builder.append(placeholder(span.type(), text.substring(span.start(), span.end())));
            cursor = span.end();
        }
        builder.append(text, cursor, text.length());
        return builder.toString();
    }

    Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    private String placeholder(PiiEntityType type, String matched) {
        counts.merge(type.metricType(), 1, Integer::sum);
        String key = type.metricType() + "\0" + matched;
        return placeholders.computeIfAbsent(key, ignored -> {
            int index = nextIndex.merge(type, 1, Integer::sum);
            return "[" + type.token() + "_" + index + "]";
        });
    }

    private static List<PiiSpan> resolve(List<PiiSpan> found) {
        List<PiiSpan> candidates = new ArrayList<>(found);
        candidates.sort(Comparator
                .comparingInt(PiiSpan::length).reversed()
                .thenComparingInt(span -> span.type().priority())
                .thenComparingInt(PiiSpan::start));
        List<PiiSpan> chosen = new ArrayList<>();
        for (PiiSpan span : candidates) {
            boolean overlaps = false;
            for (PiiSpan kept : chosen) {
                if (span.overlaps(kept)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                chosen.add(span);
            }
        }
        chosen.sort(Comparator.comparingInt(PiiSpan::start));
        return chosen;
    }
}
