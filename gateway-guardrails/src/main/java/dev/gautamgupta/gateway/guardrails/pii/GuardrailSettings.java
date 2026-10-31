package dev.gautamgupta.gateway.guardrails.pii;

import java.util.Map;

/**
 * Deployment mode, optional per-tenant overrides, and which detectors are on.
 * Tenant ids that are absent inherit {@code mode}.
 */
public record GuardrailSettings(
        PiiMode mode,
        Map<String, PiiMode> tenantModes,
        boolean email,
        boolean phone,
        boolean creditCard,
        boolean ipv4,
        boolean ipv6,
        boolean iban,
        boolean pan,
        boolean aadhaar
) {

    public GuardrailSettings {
        if (mode == null) {
            mode = PiiMode.REDACT;
        }
        tenantModes = tenantModes == null || tenantModes.isEmpty() ? Map.of() : Map.copyOf(tenantModes);
    }

    public static GuardrailSettings defaults() {
        return new GuardrailSettings(PiiMode.REDACT, Map.of(), true, true, true, true, true, true, true, true);
    }

    public PiiMode modeFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return mode;
        }
        return tenantModes.getOrDefault(tenantId, mode);
    }
}
