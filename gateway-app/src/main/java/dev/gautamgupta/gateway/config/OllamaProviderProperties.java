package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.providers.ollama.OllamaClientSettings;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ollama provider settings. The base URL is required. An API key is optional and
 * is read from the environment when a proxy in front of Ollama expects one.
 */
@ConfigurationProperties(prefix = "gateway.providers.ollama")
public class OllamaProviderProperties {

    private String baseUrl = "http://127.0.0.1:11434";
    private String apiKey = "";
    private String chatPath = "/api/chat";
    private String embedPath = "/api/embed";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration responseTimeout = Duration.ofSeconds(120);
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

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }

    public String getEmbedPath() {
        return embedPath;
    }

    public void setEmbedPath(String embedPath) {
        this.embedPath = embedPath;
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

    public OllamaClientSettings toSettings() {
        return new OllamaClientSettings(normalizedBaseUrl(), chatPath, embedPath, apiKey);
    }
}
