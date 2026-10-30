package dev.gautamgupta.gateway.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Model price table in USD per 1K tokens (FR-071). Missing models record a null cost. */
@ConfigurationProperties(prefix = "gateway")
public class GatewayPriceProperties {

    private Map<String, Quote> prices = new LinkedHashMap<>();

    public Map<String, Quote> getPrices() {
        return prices;
    }

    public void setPrices(Map<String, Quote> prices) {
        this.prices = prices;
    }

    public static class Quote {
        private BigDecimal promptPer1kUsd = BigDecimal.ZERO;
        private BigDecimal completionPer1kUsd = BigDecimal.ZERO;

        public BigDecimal getPromptPer1kUsd() {
            return promptPer1kUsd;
        }

        public void setPromptPer1kUsd(BigDecimal promptPer1kUsd) {
            this.promptPer1kUsd = promptPer1kUsd;
        }

        public BigDecimal getCompletionPer1kUsd() {
            return completionPer1kUsd;
        }

        public void setCompletionPer1kUsd(BigDecimal completionPer1kUsd) {
            this.completionPer1kUsd = completionPer1kUsd;
        }
    }
}
