package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.providers.anthropic.AnthropicClientSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic provider settings. The API key is read from the environment and is never
 * written to disk by the application.
 */
@ConfigurationProperties(prefix = "gateway.providers.anthropic")
public class AnthropicProviderProperties {

    private String baseUrl = "https://api.anthropic.com";
    private String apiKey = "";
    private String messagesPath = "/v1/messages";
    private String version = "2023-06-01";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration responseTimeout = Duration.ofSeconds(120);
    private int defaultMaxTokens = 4096;
    private int maxInMemoryBytes = 2 * 1024 * 1024;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getMessagesPath() {
        return messagesPath;
    }

    public void setMessagesPath(String messagesPath) {
        this.messagesPath = messagesPath;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public int getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    public void setDefaultMaxTokens(int defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public int getMaxInMemoryBytes() {
        return maxInMemoryBytes;
    }

    public void setMaxInMemoryBytes(int maxInMemoryBytes) {
        this.maxInMemoryBytes = maxInMemoryBytes;
    }

    public String normalizedBaseUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public AnthropicClientSettings toSettings() {
        return new AnthropicClientSettings(apiKey, messagesPath, version, defaultMaxTokens);
    }
}
