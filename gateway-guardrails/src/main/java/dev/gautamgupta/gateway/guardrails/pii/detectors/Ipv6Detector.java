package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Full and compressed IPv6. A value with no hex digit, or more than one {@code ::}, is ignored. */
public final class Ipv6Detector implements PiiDetector {

    private static final Pattern CANDIDATE = Pattern.compile(
            "(?i)(?<![0-9A-Z:])(?:[0-9A-F]{0,4}:){2,7}[0-9A-F]{0,4}(?![0-9A-Z:])");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.IPV6;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (valid(matcher.group())) {
                spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.IPV6));
            }
        }
        return spans;
    }

    static boolean valid(String value) {
        if (value.indexOf(':') < 0) {
            return false;
        }
        boolean hex = false;
        int compressions = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                if (i + 1 < value.length() && value.charAt(i + 1) == ':') {
                    compressions++;
                    i++;
                }
            } else if (isHex(c)) {
                hex = true;
            } else {
                return false;
            }
        }
        if (!hex || compressions > 1) {
            return false;
        }
        if (compressions == 1) {
            String[] sides = value.split("::", -1);
            int left = countGroups(sides[0]);
            int right = countGroups(sides[1]);
            return left >= 0 && right >= 0 && left + right < 8;
        }
        return countGroups(value) == 8;
    }

    private static int countGroups(String side) {
        if (side.isEmpty()) {
            return 0;
        }
        String[] groups = side.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4) {
                return -1;
            }
            for (int i = 0; i < group.length(); i++) {
                if (!isHex(group.charAt(i))) {
                    return -1;
                }
            }
        }
        return groups.length;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }
}
