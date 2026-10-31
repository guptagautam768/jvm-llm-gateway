package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Email with a dotted domain and a letter TLD of at least two characters. */
public final class EmailDetector implements PiiDetector {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+\\-])[A-Z0-9._%+\\-]+@[A-Z0-9](?:[A-Z0-9\\-]{0,61}[A-Z0-9])?"
                    + "(?:\\.[A-Z0-9](?:[A-Z0-9\\-]{0,61}[A-Z0-9])?)*\\.[A-Z]{2,}(?![A-Z0-9])");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.EMAIL;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = EMAIL.matcher(text);
        while (matcher.find()) {
            spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.EMAIL));
        }
        return spans;
    }
}
