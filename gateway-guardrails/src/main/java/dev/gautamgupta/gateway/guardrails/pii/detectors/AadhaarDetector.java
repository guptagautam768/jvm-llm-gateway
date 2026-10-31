package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import dev.gautamgupta.gateway.guardrails.pii.checksum.Verhoeff;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aadhaar: 12 digits, first digit 2-9, optional grouping {@code XXXX XXXX XXXX},
 * kept only when the Verhoeff check passes.
 */
public final class AadhaarDetector implements PiiDetector {

    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)[2-9]\\d{3}[ \\t]?\\d{4}[ \\t]?\\d{4}(?!\\d)");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.AADHAAR;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String digits = digits(matcher.group());
            if (digits.length() == 12 && Verhoeff.valid(digits)) {
                spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.AADHAAR));
            }
        }
        return spans;
    }

    private static String digits(String raw) {
        StringBuilder builder = new StringBuilder(12);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
