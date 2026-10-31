package dev.gautamgupta.gateway.guardrails.pii;

/** ADR-0009 modes. {@code REDACT} is the deployment default. */
public enum PiiMode {
    REDACT,
    REJECT,
    OFF
}
