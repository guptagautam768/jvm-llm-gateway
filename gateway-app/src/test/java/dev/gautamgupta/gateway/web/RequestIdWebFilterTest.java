package dev.gautamgupta.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestIdWebFilterTest {

    @Test
    void keepsASafeCallerIdAndReplacesInjection() {
        assertThat(RequestIdWebFilter.sanitize("req-123")).isEqualTo("req-123");
        assertThat(RequestIdWebFilter.sanitize("bad\r\nX-Evil: 1"))
                .isNotEqualTo("bad\r\nX-Evil: 1")
                .hasSize(36);
        assertThat(RequestIdWebFilter.sanitize(null)).hasSize(36);
    }
}
