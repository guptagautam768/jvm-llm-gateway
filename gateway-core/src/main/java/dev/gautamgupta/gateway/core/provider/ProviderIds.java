package dev.gautamgupta.gateway.core.provider;

/**
 * Stable provider ids used by routing config (ADR-0005).
 * OpenAI and Anthropic have adapters. Ollama is reserved until that adapter exists.
 */
public final class ProviderIds {

    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String OLLAMA = "ollama";

    private ProviderIds() {
    }
}
