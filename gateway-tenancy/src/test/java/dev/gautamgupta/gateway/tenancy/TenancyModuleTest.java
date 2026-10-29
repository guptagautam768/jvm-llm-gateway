package dev.gautamgupta.gateway.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenancyModuleTest {

    @Test
    void keepsAStableModuleId() {
        assertThat(TenancyModule.ID).isEqualTo("gateway-tenancy");
    }
}
