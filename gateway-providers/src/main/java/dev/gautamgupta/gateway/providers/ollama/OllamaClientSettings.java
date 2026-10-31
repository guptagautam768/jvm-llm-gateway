package dev.gautamgupta.gateway.providers.ollama;

/**
 * Connection details the Ollama adapter needs. The base URL and timeouts live on
 * the {@code WebClient}. An API key is optional; local Ollama does not require one.
 */
public record OllamaClientSettings(String baseUrl, String chatPath, String embedPath, String apiKey) {

    public OllamaClientSettings {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        if (chatPath == null || chatPath.isBlank()) {
            chatPath = "/api/chat";
        }
        if (embedPath == null || embedPath.isBlank()) {
            embedPath = "/api/embed";
        }
        apiKey = apiKey == null ? "" : stripBearer(apiKey.trim());
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    private static String stripBearer(String value) {
        if (value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return value.substring("Bearer ".length()).trim();
        }
        return value;
    }
}
