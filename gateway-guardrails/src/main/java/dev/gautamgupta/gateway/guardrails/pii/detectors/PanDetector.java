package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indian PAN: five letters, four digits, one letter. The fourth character is the
 * holder type (P, C, H, F, A, T, B, L, J, G).
 */
public final class PanDetector implements PiiDetector {

    private static final Pattern PAN = Pattern.compile(
            "(?i)(?<![A-Z0-9])[A-Z]{3}[ABCFGHLJPT][A-Z]\\d{4}[A-Z](?![A-Z0-9])");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.PAN;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = PAN.matcher(text);
        while (matcher.find()) {
            spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.PAN));
        }
        return spans;
    }
}
