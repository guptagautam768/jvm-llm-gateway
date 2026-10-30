package dev.gautamgupta.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Caller identity and limits (CFG-005). Bootstrap secrets come from the environment.
 * They are hashed before insert and are not a default in this file.
 */
@ConfigurationProperties(prefix = "gateway.tenancy")
public class TenancyProperties {

    private boolean enabled = true;
    private Duration cacheTtl = Duration.ofSeconds(30);
    private long cacheMaximumSize = 10_000;
    private int defaultCompletionTokens = 256;
    private boolean metricsTenantTag = false;
    private List<BootstrapTenant> bootstrap = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public long getCacheMaximumSize() {
        return cacheMaximumSize;
    }

    public void setCacheMaximumSize(long cacheMaximumSize) {
        this.cacheMaximumSize = cacheMaximumSize;
    }

    public int getDefaultCompletionTokens() {
        return defaultCompletionTokens;
    }

    public void setDefaultCompletionTokens(int defaultCompletionTokens) {
        this.defaultCompletionTokens = defaultCompletionTokens;
    }

    public boolean isMetricsTenantTag() {
        return metricsTenantTag;
    }

    public void setMetricsTenantTag(boolean metricsTenantTag) {
        this.metricsTenantTag = metricsTenantTag;
    }

    public List<BootstrapTenant> getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(List<BootstrapTenant> bootstrap) {
        this.bootstrap = bootstrap;
    }

    public static class BootstrapTenant {
        private String tenantId = "";
        private String displayName = "";
        private String status = "active";
        private List<BootstrapKey> keys = new ArrayList<>();
        private BootstrapLimits limits = new BootstrapLimits();

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public List<BootstrapKey> getKeys() {
            return keys;
        }

        public void setKeys(List<BootstrapKey> keys) {
            this.keys = keys;
        }

        public BootstrapLimits getLimits() {
            return limits;
        }

        public void setLimits(BootstrapLimits limits) {
            this.limits = limits;
        }
    }

    public static class BootstrapKey {
        private String keyId = "";
        private String secret = "";

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class BootstrapLimits {
        private int rateCapacity = 60;
        private double rateRefillPerSecond = 1;
        private String tokensPerMinute = "";
        private String tokenBudget = "";
        private String costBudgetMicros = "";
        private Duration budgetWindow = Duration.ofDays(30);

        public int getRateCapacity() {
            return rateCapacity;
        }

        public void setRateCapacity(int rateCapacity) {
            this.rateCapacity = rateCapacity;
        }

        public double getRateRefillPerSecond() {
            return rateRefillPerSecond;
        }

        public void setRateRefillPerSecond(double rateRefillPerSecond) {
            this.rateRefillPerSecond = rateRefillPerSecond;
        }

        public String getTokensPerMinute() {
            return tokensPerMinute;
        }

        public void setTokensPerMinute(String tokensPerMinute) {
            this.tokensPerMinute = tokensPerMinute;
        }

        public String getTokenBudget() {
            return tokenBudget;
        }

        public void setTokenBudget(String tokenBudget) {
            this.tokenBudget = tokenBudget;
        }

        public String getCostBudgetMicros() {
            return costBudgetMicros;
        }

        public void setCostBudgetMicros(String costBudgetMicros) {
            this.costBudgetMicros = costBudgetMicros;
        }

        public Duration getBudgetWindow() {
            return budgetWindow;
        }

        public void setBudgetWindow(Duration budgetWindow) {
            this.budgetWindow = budgetWindow;
        }
    }
}
