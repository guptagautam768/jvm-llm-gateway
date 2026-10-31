package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.cache.CacheSettings;
import dev.gautamgupta.gateway.cache.SemanticThreshold;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code gateway.cache} (ADR-0006, CFG-004). */
@ConfigurationProperties(prefix = "gateway.cache")
public class CacheProperties {

    private boolean enabled = true;
    private Exact exact = new Exact();
    private Semantic semantic = new Semantic();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Exact getExact() {
        return exact;
    }

    public void setExact(Exact exact) {
        this.exact = exact == null ? new Exact() : exact;
    }

    public Semantic getSemantic() {
        return semantic;
    }

    public void setSemantic(Semantic semantic) {
        this.semantic = semantic == null ? new Semantic() : semantic;
    }

    public CacheSettings toSettings() {
        double threshold = semantic.getThreshold();
        if (Double.isNaN(threshold) || threshold < 0.0d || threshold > 1.0d) {
            throw new IllegalStateException("gateway.cache.semantic.threshold must be between 0 and 1");
        }
        double maxTemperature = semantic.getMaxTemperature();
        if (Double.isNaN(maxTemperature) || maxTemperature < 0.0d || maxTemperature > 2.0d) {
            throw new IllegalStateException("gateway.cache.semantic.max-temperature must be between 0 and 2");
        }
        return new CacheSettings(
                exact.getTtl(),
                exact.getMaxEntries(),
                semantic.isEnabled(),
                semantic.getTtl(),
                semantic.getMaxEntries(),
                semantic.getDimensions());
    }

    public static class Exact {
        private Duration ttl = Duration.ofMinutes(10);
        private int maxEntries = 10_000;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    public static class Semantic {
        private boolean enabled = true;
        private Duration ttl = Duration.ofMinutes(10);
        private int maxEntries = 10_000;
        private double threshold = SemanticThreshold.DEFAULT;
        private double maxTemperature = SemanticThreshold.MAX_TEMPERATURE;
        private int dimensions = 1536;
        private String embeddingProvider = "openai";
        private String embeddingModel = "text-embedding-3-small";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }

        public double getMaxTemperature() {
            return maxTemperature;
        }

        public void setMaxTemperature(double maxTemperature) {
            this.maxTemperature = maxTemperature;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public String getEmbeddingProvider() {
            return embeddingProvider;
        }

        public void setEmbeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }
}
