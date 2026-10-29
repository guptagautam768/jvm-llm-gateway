package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.providers.openai.OpenAiClientSettings;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI provider settings. The API key is read from the environment and is never
 * written to disk by the application.
 */
@ConfigurationProperties(prefix = "gateway.providers.openai")
public class OpenAiProviderProperties {

    private String baseUrl = "https://api.openai.com";
    private String apiKey = "";
    private String completionsPath = "/v1/chat/completions";
    private String modelsPath = "/v1/models";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration responseTimeout = Duration.ofSeconds(60);
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

    public String getCompletionsPath() {
        return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
        this.completionsPath = completionsPath;
    }

    public String getModelsPath() {
        return modelsPath;
    }

    public void setModelsPath(String modelsPath) {
        this.modelsPath = modelsPath;
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

    public URI baseUri() {
        return URI.create(normalizedBaseUrl());
    }

    public OpenAiClientSettings toSettings() {
        return new OpenAiClientSettings(apiKey, completionsPath, modelsPath);
    }
}
