package dev.gautamgupta.gateway.guardrails;

/**
 * PII redaction and request size limits (ADR-0009).
 *
 * <p>The chat pipeline runs {@link RequestSizeLimit}, then {@link PromptRedactor}, then
 * the cache. Raw prompt text must not be logged. Audit data is entity type and count.
 */
public final class GuardrailsModule {

    public static final String ID = "gateway-guardrails";

    private GuardrailsModule() {
    }
}
