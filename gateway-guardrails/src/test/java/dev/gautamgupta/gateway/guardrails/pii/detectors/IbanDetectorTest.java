package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IbanDetectorTest {

    private final IbanDetector detector = new IbanDetector();

    @Test
    void findsChecksumValidIbansWithOrWithoutSpaces() {
        assertThat(detector.detect("pay DE89370400440532013000 thanks")).hasSize(1);
        assertThat(detector.detect("pay GB82 WEST 1234 5698 7654 32 thanks")).hasSize(1);
        assertThat(detector.detect("pay de89370400440532013000 thanks")).hasSize(1);
    }

    @Test
    void ignoresBadChecksumsAndShortTokens() {
        assertThat(detector.detect("pay DE89370400440532013001 thanks")).isEmpty();
        assertThat(detector.detect("code AB12CD")).isEmpty();
        assertThat(detector.detect("not an iban")).isEmpty();
        assertThat(detector.detect("DE89")).isEmpty();
    }
}
