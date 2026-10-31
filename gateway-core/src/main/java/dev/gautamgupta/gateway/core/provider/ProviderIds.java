package dev.gautamgupta.gateway.core.provider;

/**
 * Stable provider ids used by routing config (ADR-0005).
 * OpenAI, Anthropic, and Ollama each have an adapter. Ids are stable in YAML.
 */
public final class ProviderIds {

    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String OLLAMA = "ollama";

    private ProviderIds() {
    }
}
