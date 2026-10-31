package dev.gautamgupta.gateway.guardrails.pii.detectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Ipv4DetectorTest {

    private final Ipv4Detector detector = new Ipv4Detector();

    @Test
    void findsDottedQuads() {
        assertThat(detector.detect("host 192.168.0.1 and 10.0.0.255")).hasSize(2);
        assertThat(detector.detect("edge 255.255.255.255")).hasSize(1);
        assertThat(detector.detect("zero 0.0.0.0")).hasSize(1);
    }

    @Test
    void ignoresOutOfRangeAndShortVersions() {
        assertThat(detector.detect("bad 999.1.1.1")).isEmpty();
        assertThat(detector.detect("bad 10.0.0.256")).isEmpty();
        assertThat(detector.detect("version 1.2.3")).isEmpty();
        assertThat(detector.detect("version 1.2")).isEmpty();
        assertThat(detector.detect("price 10.00")).isEmpty();
    }
}
