package dev.gautamgupta.gateway.guardrails;

/**
 * PII redaction and request size limits (ADR-0009).
 *
 * <p>Week 4 leaves {@link PromptRedactor} and {@link RequestSizeLimit} as no-ops so the
 * chat pipeline already runs size check, then redaction, then the cache. Week 5 fills
 * both in. Raw prompt text must not be logged.
 */
public final class GuardrailsModule {

    public static final String ID = "gateway-guardrails";

    private GuardrailsModule() {
    }
}
