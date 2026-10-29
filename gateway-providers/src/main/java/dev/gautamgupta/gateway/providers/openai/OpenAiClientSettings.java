package dev.gautamgupta.gateway.providers.openai;

/**
 * Connection details the OpenAI adapter needs. The base URL and timeouts live on the
 * {@code WebClient} so this type stays free of HTTP client classes.
 */
public record OpenAiClientSettings(String apiKey, String completionsPath, String modelsPath) {

    public OpenAiClientSettings {
        apiKey = apiKey == null ? "" : stripBearer(apiKey.trim());
        if (completionsPath == null || completionsPath.isBlank()) {
            completionsPath = "/v1/chat/completions";
        }
        if (modelsPath == null || modelsPath.isBlank()) {
            modelsPath = "/v1/models";
        }
    }

    private static String stripBearer(String value) {
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return value.substring("Bearer ".length()).trim();
        }
        return value;
    }
}
