package dev.gautamgupta.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheModuleTest {

    @Test
    void keepsAStableModuleId() {
        assertThat(CacheModule.ID).isEqualTo("gateway-cache");
    }
}
