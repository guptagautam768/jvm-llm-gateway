package dev.gautamgupta.gateway.providers.anthropic;

/**
 * Connection details the Anthropic adapter needs. The base URL and timeouts live on
 * the {@code WebClient}.
 */
public record AnthropicClientSettings(String apiKey, String messagesPath, String version, int defaultMaxTokens) {

    public AnthropicClientSettings {
        apiKey = apiKey == null ? "" : stripBearer(apiKey.trim());
        if (messagesPath == null || messagesPath.isBlank()) {
            messagesPath = "/v1/messages";
        }
        if (version == null || version.isBlank()) {
            version = "2023-06-01";
        }
        if (defaultMaxTokens < 1) {
            throw new IllegalArgumentException("anthropic default-max-tokens must be at least 1");
        }
    }

    private static String stripBearer(String value) {
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return value.substring("Bearer ".length()).trim();
        }
        return value;
    }
}
