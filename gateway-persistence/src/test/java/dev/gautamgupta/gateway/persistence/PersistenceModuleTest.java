package dev.gautamgupta.gateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersistenceModuleTest {

    @Test
    void keepsAStableModuleId() {
        assertThat(PersistenceModule.ID).isEqualTo("gateway-persistence");
    }
}
