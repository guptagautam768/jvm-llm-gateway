package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.guardrails.pii.PiiEntityType;
import org.junit.jupiter.api.Test;

class CreditCardDetectorTest {

    private final CreditCardDetector detector = new CreditCardDetector();

    @Test
    void keepsLuhnValidCardsIncludingGroupedOnes() {
        assertThat(detector.detect("card 4111111111111111 end")).singleElement()
                .extracting(span -> span.type())
                .isEqualTo(PiiEntityType.CREDIT_CARD);
        assertThat(detector.detect("card 4111-1111-1111-1111 end")).hasSize(1);
        assertThat(detector.detect("card 4111 1111 1111 1111 end")).hasSize(1);
    }

    @Test
    void rejectsNumbersThatFailLuhnOrAreTheWrongLength() {
        assertThat(detector.detect("card 4111111111111112 end")).isEmpty();
        assertThat(detector.detect("order 1234567890123456")).isEmpty();
        assertThat(detector.detect("short 411111111111")).isEmpty();
        assertThat(detector.detect("date 2024-01-01")).isEmpty();
        assertThat(detector.detect("budget 12345")).isEmpty();
    }
}
