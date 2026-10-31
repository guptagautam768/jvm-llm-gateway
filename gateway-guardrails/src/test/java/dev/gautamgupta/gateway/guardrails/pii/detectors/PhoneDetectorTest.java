package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoneDetectorTest {

    private final PhoneDetector detector = new PhoneDetector();

    @Test
    void findsE164AndIndianFormats() {
        assertFound("+1 202 555 0143", "+1 202 555 0143");
        assertFound("call +442079460958 today", "+442079460958");
        assertFound("mobile +91 9876543210", "+91 9876543210");
        assertFound("mobile +91-98765-43210", "+91-98765-43210");
        assertFound("trunk 09876543210", "09876543210");
        assertFound("local 9876543210", "9876543210");
        assertFound("grouped 98765 43210", "98765 43210");
        assertFound("delhi 011 23456789", "011 23456789");
        assertFound("compact 01123456789", "01123456789");
    }

    @Test
    void ignoresShortIdsYearsAndNonIndianNationals() {
        assertThat(detector.detect("order 12345")).isEmpty();
        assertThat(detector.detect("year 2024")).isEmpty();
        assertThat(detector.detect("short +1234")).isEmpty();
        assertThat(detector.detect("id 1234567890")).isEmpty();
        assertThat(detector.detect("partial 987654")).isEmpty();
        assertThat(detector.detect("nine 987654321")).isEmpty();
        assertThat(detector.detect("version 1.2.3")).isEmpty();
        assertThat(detector.detect("call me later")).isEmpty();
    }

    private void assertFound(String text, String expected) {
        List<PiiSpan> spans = detector.detect(text);
        assertThat(spans).as(text).isNotEmpty();
        assertThat(text.substring(spans.get(0).start(), spans.get(0).end())).isEqualTo(expected);
    }
}
