package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fail-fast checks that ADR-0005's route table does not cover: base URLs, timeouts,
 * and the semantic-cache embedding provider (ADR-0015, Proposed).
 */
public final class GatewayStartupChecks {

    private GatewayStartupChecks() {
    }

    public static List<String> problems(
            OpenAiProviderProperties openai,
            AnthropicProviderProperties anthropic,
            OllamaProviderProperties ollama,
            CacheProperties cache,
            ProviderRegistry registry
    ) {
        List<String> problems = new ArrayList<>();
        requireUrl(problems, "gateway.providers.openai.base-url", openai.normalizedBaseUrl());
        requireUrl(problems, "gateway.providers.anthropic.base-url", anthropic.normalizedBaseUrl());
        requireUrl(problems, "gateway.providers.ollama.base-url", ollama.normalizedBaseUrl());
        requirePath(problems, "gateway.providers.openai.completions-path", openai.getCompletionsPath());
        requirePath(problems, "gateway.providers.openai.embeddings-path", openai.getEmbeddingsPath());
        requirePath(problems, "gateway.providers.anthropic.messages-path", anthropic.getMessagesPath());
        requirePath(problems, "gateway.providers.ollama.chat-path", ollama.getChatPath());
        requirePath(problems, "gateway.providers.ollama.embed-path", ollama.getEmbedPath());
        requireTimeout(problems, "gateway.providers.openai.connect-timeout", openai.getConnectTimeout());
        requireTimeout(problems, "gateway.providers.openai.response-timeout", openai.getResponseTimeout());
        requireTimeout(problems, "gateway.providers.anthropic.connect-timeout", anthropic.getConnectTimeout());
        requireTimeout(problems, "gateway.providers.anthropic.response-timeout", anthropic.getResponseTimeout());
        requireTimeout(problems, "gateway.providers.ollama.connect-timeout", ollama.getConnectTimeout());
        requireTimeout(problems, "gateway.providers.ollama.response-timeout", ollama.getResponseTimeout());
        requireBytes(problems, "gateway.providers.openai.max-in-memory-bytes", openai.getMaxInMemoryBytes());
        requireBytes(problems, "gateway.providers.anthropic.max-in-memory-bytes", anthropic.getMaxInMemoryBytes());
        requireBytes(problems, "gateway.providers.ollama.max-in-memory-bytes", ollama.getMaxInMemoryBytes());
        cache(problems, cache, registry);
        return problems;
    }

    /**
     * Semantic cache is a miss when the embedding provider has no credentials.
     * An unknown provider id is a configuration error and fails startup.
     */
    public static boolean semanticProviderReady(ProviderRegistry registry, String provider) {
        if (registry == null || provider == null || provider.isBlank()) {
            return false;
        }
        try {
            ProviderClient client = registry.get(provider);
            if (!client.capabilities().embeddings()) {
                return false;
            }
            client.ensureConfigured();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void cache(List<String> problems, CacheProperties cache, ProviderRegistry registry) {
        if (cache == null) {
            problems.add("gateway.cache is required");
            return;
        }
        CacheProperties.Exact exact = cache.getExact();
        CacheProperties.Semantic semantic = cache.getSemantic();
        requireTimeout(problems, "gateway.cache.exact.ttl", exact.getTtl());
        if (exact.getMaxEntries() < 1) {
            problems.add("gateway.cache.exact.max-entries must be at least 1");
        }
        requireTimeout(problems, "gateway.cache.semantic.ttl", semantic.getTtl());
        if (semantic.getMaxEntries() < 1) {
            problems.add("gateway.cache.semantic.max-entries must be at least 1");
        }
        if (semantic.getDimensions() < 1) {
            problems.add("gateway.cache.semantic.dimensions must be at least 1");
        }
        double threshold = semantic.getThreshold();
        if (Double.isNaN(threshold) || threshold <= 0.0d || threshold > 1.0d) {
            problems.add("gateway.cache.semantic.threshold must be in (0, 1]");
        }
        double maxTemperature = semantic.getMaxTemperature();
        if (Double.isNaN(maxTemperature) || maxTemperature < 0.0d || maxTemperature > 2.0d) {
            problems.add("gateway.cache.semantic.max-temperature must be between 0 and 2");
        }
        if (!semantic.isEnabled()) {
            return;
        }
        String provider = semantic.getEmbeddingProvider();
        if (provider == null || provider.isBlank()) {
            problems.add("gateway.cache.semantic.embedding-provider is required when semantic cache is enabled");
            return;
        }
        if (semantic.getEmbeddingModel() == null || semantic.getEmbeddingModel().isBlank()) {
            problems.add("gateway.cache.semantic.embedding-model is required when semantic cache is enabled");
        }
        try {
            ProviderClient client = registry.get(provider);
            if (!client.capabilities().embeddings()) {
                problems.add("gateway.cache.semantic.embedding-provider " + provider + " cannot embed");
            }
        } catch (IllegalArgumentException ex) {
            problems.add("gateway.cache.semantic.embedding-provider " + provider + " is not a known provider");
        }
    }

    private static void requireUrl(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            problems.add(name + " is not an absolute http(s) URL");
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            problems.add(name + " must be an absolute http or https URL");
        }
    }

    private static void requirePath(List<String> problems, String name, String value) {
        if (value == null || !value.startsWith("/")) {
            problems.add(name + " must start with /");
        }
    }

    private static void requireTimeout(List<String> problems, String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            problems.add(name + " must be positive");
        }
    }

    private static void requireBytes(List<String> problems, String name, int value) {
        if (value < 1) {
            problems.add(name + " must be at least 1");
        }
    }
}
