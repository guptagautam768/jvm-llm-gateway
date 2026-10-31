package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Ipv6DetectorTest {

    private final Ipv6Detector detector = new Ipv6Detector();

    @Test
    void findsFullAndCompressedAddresses() {
        assertThat(detector.detect("src 2001:db8::1 dst ::1")).hasSize(2);
        assertThat(detector.detect("full 2001:0db8:0000:0000:0000:0000:0000:0001")).hasSize(1);
        assertThat(detector.detect("link fe80::1")).hasSize(1);
    }

    @Test
    void ignoresIncompleteOrDoublyCompressedText() {
        assertThat(detector.detect("short 1:2")).isEmpty();
        assertThat(detector.detect("three 1:2:3")).isEmpty();
        assertThat(detector.detect("empty ::")).isEmpty();
        assertThat(detector.detect("double 1::2::3")).isEmpty();
        assertThat(detector.detect("letters gggg::1")).isEmpty();
        assertThat(Ipv6Detector.valid("::")).isFalse();
        assertThat(Ipv6Detector.valid("::1")).isTrue();
    }
}
