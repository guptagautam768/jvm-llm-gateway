package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import dev.gautamgupta.gateway.guardrails.pii.checksum.IbanCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Country code, check digits, and BBAN, kept only when the mod-97 check passes. Spaces are allowed. */
public final class IbanDetector implements PiiDetector {

    private static final Pattern COMPACT = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{2}\\d{2}[A-Z0-9]{11,30}(?![A-Z0-9])");
    private static final Pattern SPACED = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ \\t][A-Z0-9]{4})+[ \\t][A-Z0-9]{1,4}(?![A-Z0-9])");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.IBAN;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher compact = COMPACT.matcher(text);
        while (compact.find()) {
            if (IbanCheck.valid(compact(compact.group()))) {
                spans.add(new PiiSpan(compact.start(), compact.end(), PiiEntityType.IBAN));
            }
        }
        Matcher spaced = SPACED.matcher(text);
        while (spaced.find()) {
            if (overlaps(spans, spaced.start(), spaced.end())) {
                continue;
            }
            if (IbanCheck.valid(compact(spaced.group()))) {
                spans.add(new PiiSpan(spaced.start(), spaced.end(), PiiEntityType.IBAN));
            }
        }
        return spans;
    }

    private static boolean overlaps(List<PiiSpan> spans, int start, int end) {
        for (PiiSpan span : spans) {
            if (start < span.end() && span.start() < end) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '\t') {
                continue;
            }
            if (c >= 'a' && c <= 'z') {
                builder.append((char) (c - 32));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
