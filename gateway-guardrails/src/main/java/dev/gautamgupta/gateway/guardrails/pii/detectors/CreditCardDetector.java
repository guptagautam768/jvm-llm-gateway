package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import dev.gautamgupta.gateway.guardrails.pii.checksum.Luhn;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 13 to 19 digits, optional single spaces or dashes, kept only when Luhn passes. */
public final class CreditCardDetector implements PiiDetector {

    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ \\t-]?){12,18}\\d(?!\\d)");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.CREDIT_CARD;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            if (separatorsAreSingle(raw) && Luhn.valid(digits(raw))) {
                spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.CREDIT_CARD));
            }
        }
        return spans;
    }

    private static boolean separatorsAreSingle(String raw) {
        boolean previousSeparator = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean separator = c == ' ' || c == '\t' || c == '-';
            if (separator && previousSeparator) {
                return false;
            }
            if (c != ' ' && c != '\t' && c != '-' && !Character.isDigit(c)) {
                return false;
            }
            previousSeparator = separator;
        }
        return true;
    }

    private static String digits(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
