package dev.gautamgupta.gateway.core.provider;

/**
 * Stable provider ids used by routing config (ADR-0005).
 * Only {@link #OPENAI} has an adapter in week 1.
 */
public final class ProviderIds {

    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String OLLAMA = "ollama";

    private ProviderIds() {
    }
}
