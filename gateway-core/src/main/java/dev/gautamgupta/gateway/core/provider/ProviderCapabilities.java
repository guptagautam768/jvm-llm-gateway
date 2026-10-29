package dev.gautamgupta.gateway.core.provider;

/** What a provider can serve. Routing will reject targets that lack a required capability (ADR-0004). */
public record ProviderCapabilities(boolean chat, boolean stream, boolean embeddings) {
}
