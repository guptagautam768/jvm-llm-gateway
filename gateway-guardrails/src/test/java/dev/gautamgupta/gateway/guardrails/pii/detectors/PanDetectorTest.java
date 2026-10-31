package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PanDetectorTest {

    private final PanDetector detector = new PanDetector();

    @Test
    void findsAPanWhoseFourthCharacterIsAHolderType() {
        assertThat(detector.detect("pan ABCPD1234F on file")).hasSize(1);
        assertThat(detector.detect("pan abcpd1234f on file")).hasSize(1);
    }

    @Test
    void ignoresCodesWithTheWrongShape() {
        assertThat(detector.detect("code ABCDE1234F")).isEmpty();
        assertThat(detector.detect("short ABCPD1234")).isEmpty();
        assertThat(detector.detect("long ABCPD12345F")).isEmpty();
        assertThat(detector.detect("hello ABC")).isEmpty();
        assertThat(detector.detect("word ABCDEFGHIJ")).isEmpty();
    }
}
