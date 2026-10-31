package dev.gautamgupta.gateway.guardrails.pii.detectors;

import dev.gautamgupta.gateway.guardrails.pii.PiiDetector;
import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dotted quad with each octet in 0..255. */
public final class Ipv4Detector implements PiiDetector {

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile("(?<!\\d)(?:" + OCTET + "\\.){3}" + OCTET + "(?!\\d)");

    @Override
    public PiiEntityType type() {
        return PiiEntityType.IPV4;
    }

    @Override
    public List<PiiSpan> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<PiiSpan> spans = new ArrayList<>();
        Matcher matcher = IPV4.matcher(text);
        while (matcher.find()) {
            spans.add(new PiiSpan(matcher.start(), matcher.end(), PiiEntityType.IPV4));
        }
        return spans;
    }
}
