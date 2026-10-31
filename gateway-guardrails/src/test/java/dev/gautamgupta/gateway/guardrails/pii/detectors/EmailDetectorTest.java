package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import dev.gautamgupta.gateway.guardrails.pii.PiiSpan;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailDetectorTest {

    private final EmailDetector detector = new EmailDetector();

    @Test
    void findsCommonAddresses() {
        List<PiiSpan> spans = detector.detect("Mail ada.lovelace@example.com and first.last+tag@example.co.uk.");
        assertThat(spans).hasSize(2);
        assertThat(spans).allMatch(span -> span.type() == PiiEntityType.EMAIL);
        assertThat(slice("Mail ada.lovelace@example.com and first.last+tag@example.co.uk.", spans.get(0)))
                .isEqualTo("ada.lovelace@example.com");
        assertThat(slice("Mail ada.lovelace@example.com and first.last+tag@example.co.uk.", spans.get(1)))
                .isEqualTo("first.last+tag@example.co.uk");
    }

    @Test
    void ignoresStringsThatOnlyLookRelated() {
        assertThat(detector.detect("not-an-email")).isEmpty();
        assertThat(detector.detect("user@localhost")).isEmpty();
        assertThat(detector.detect("foo@bar")).isEmpty();
        assertThat(detector.detect("just@")).isEmpty();
        assertThat(detector.detect("a@b.c")).isEmpty();
        assertThat(detector.detect("version 1.2.3 and price 10.00")).isEmpty();
    }

    private static String slice(String text, PiiSpan span) {
        return text.substring(span.start(), span.end());
    }
}
