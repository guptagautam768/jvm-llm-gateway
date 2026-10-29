package dev.gautamgupta.gateway.guardrails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuardrailsModuleTest {

    @Test
    void keepsAStableModuleId() {
        assertThat(GuardrailsModule.ID).isEqualTo("gateway-guardrails");
    }
}
