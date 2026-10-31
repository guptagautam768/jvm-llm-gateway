package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E.164-ish numbers (leading {@code +}, 8 to 15 digits) and Indian national numbers:
 * mobiles {@code [6-9]XXXXXXXXX} with an optional trunk {@code 0} or a single separator,
 * and 11-digit STD numbers that start with {@code 0}.
 */
public final class PhoneDetector implements PiiDetector {

    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)0?[6-9]\\d{9}(?!\\d)");
    private static final Pattern MOBILE_GROUPED = Pattern.compile("(?<!\\d)[6-9]\\d{4}[ \\t-]\\d{5}(?!\\d)");
    private static final Pattern LANDLINE = Pattern.compile("(?<!\\d)0[1-9]\\d{1,3}[ \\t-]\\d{6,8}(?!\\d)");
    private static final Pattern LANDLINE_COMPACT = Pattern.compile("(?<!\\d)0[1-9]\\d{9}(?!\\d)");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.PHONE;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        scanInternational(text, spans);
        collect(MOBILE.matcher(text), spans);
        collect(MOBILE_GROUPED.matcher(text), spans);
        Matcher landline = LANDLINE.matcher(text);
        while (landline.find()) {
            if (digits(landline.group()) == 11) {
                spans.add(new PiiSpan(landline.start(), landline.end(), PiiEntityType.PHONE));
            }
        }
        collect(LANDLINE_COMPACT.matcher(text), spans);
        return spans;
    }

    private static void collect(Matcher matcher, List<PiiSpan> spans) {
        while (matcher.find()) {
            spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.PHONE));
        }
    }

    private static void scanInternational(String text, List<PiiSpan> spans) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '+') {
                continue;
            }
            if (i > 0 && Character.isDigit(text.charAt(i - 1))) {
                continue;
            }
            int start = i;
            int j = i + 1;
            if (j >= text.length() || text.charAt(j) < '1' || text.charAt(j) > '9') {
                continue;
            }
            j++;
            boolean doubleSeparator = false;
            while (j < text.length()) {
                char c = text.charAt(j);
                if (Character.isDigit(c)) {
                    j++;
                    continue;
                }
                if (c == ' ' || c == '\t' || c == '.' || c == '-') {
                    if (j > start && isSeparator(text.charAt(j - 1))) {
                        doubleSeparator = true;
                        break;
                    }
                    j++;
                    continue;
                }
                break;
            }
            if (doubleSeparator) {
                continue;
            }
            int end = j;
            while (end > start + 1 && isSeparator(text.charAt(end - 1))) {
                end--;
            }
            int count = digits(text.substring(start, end));
            if (count >= 8 && count <= 15) {
                spans.add(new PiiSpan(start, end, PiiEntityType.PHONE));
                i = end - 1;
            }
        }
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '.' || c == '-';
    }

    private static int digits(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
