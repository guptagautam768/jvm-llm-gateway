package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.guardrails.pii.checksum.Verhoeff;
import org.junit.jupiter.api.Test;

class AadhaarDetectorTest {

    private final AadhaarDetector detector = new AadhaarDetector();

    @Test
    void verhoeffRoundTripMatchesThePublishedExample() {
        assertThat(Verhoeff.valid("2363")).isTrue();
        assertThat(Verhoeff.checkDigit("236")).isEqualTo('3');
        assertThat(Verhoeff.valid("2364")).isFalse();
    }

    @Test
    void findsAChecksumValidAadhaarIncludingGroupedForm() {
        String digits = aadhaar("23456789012");
        assertThat(detector.detect("uid " + digits + " end")).hasSize(1);
        String grouped = digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8);
        assertThat(detector.detect("uid " + grouped + " end")).hasSize(1);
    }

    @Test
    void ignoresFailedChecksumsAndTheWrongLength() {
        assertThat(detector.detect("uid 234567890121 end")).isEmpty();
        assertThat(detector.detect("uid 111111111111 end")).isEmpty();
        assertThat(detector.detect("uid 12345678901 end")).isEmpty();
        assertThat(detector.detect("uid 012345678901 end")).isEmpty();
        assertThat(detector.detect("order 12345")).isEmpty();
    }

    private static String aadhaar(String payload) {
        return payload + Verhoeff.checkDigit(payload);
    }
}
