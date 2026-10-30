package dev.gautamgupta.gateway.tenancy.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.tenancy.limit.RateLimitDecision;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetMathTest {

    @Test
    void estimatesPromptCharactersPlusReservedCompletion() {
        TokenEstimator estimator = new TokenEstimator(16);
        UsageEstimate estimate = estimator.estimate(request("Hello", 100));

        assertThat(estimate.promptTokens()).isEqualTo(2);
        assertThat(estimate.completionTokens()).isEqualTo(100);
        assertThat(estimator.estimate(request("Hello", null)).completionTokens()).isEqualTo(16);
    }

    @Test
    void pricesMicroDollarsAndLeavesUnknownModelsEmpty() {
        ModelPrice price = new ModelPrice(new BigDecimal("0.002"), new BigDecimal("0.008"));
        assertThat(price.micros(5, 7)).isEqualTo(66);

        PriceCatalog catalog = new PriceCatalog(Map.of("gpt-4o-mini", price));
        assertThat(catalog.costMicros("gpt-4o-mini", 5, 7)).contains(66L);
        assertThat(catalog.find("missing")).isEmpty();
    }

    @Test
    void windowKeyIsStableInsideTheWindow() {
        Duration window = Duration.ofDays(30);
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        long start = BudgetWindow.startEpochSeconds(now, window);
        assertThat(start).isLessThanOrEqualTo(now.getEpochSecond());
        assertThat(now.getEpochSecond() - start).isLessThan(window.getSeconds());
        assertThat(BudgetWindow.redisKey("acme", now, window)).isEqualTo("budget:acme:" + start);
        assertThat(BudgetWindow.secondsUntilEnd(now, window)).isPositive();
    }

    @Test
    void parsesLimiterAndBudgetScripts() {
        RateLimitDecision allowed = RateLimitDecision.parse("1|4|0|10|1700000000000");
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.remaining()).isEqualTo(4);
        assertThat(allowed.limit()).isEqualTo(10);
        assertThat(allowed.retryAfterSeconds()).isZero();
        assertThat(allowed.resetEpochSeconds()).isEqualTo(1_700_000_000L);

        RateLimitDecision denied = RateLimitDecision.parse("0|0|1500|10|1700000001500");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(2);

        assertThat(BudgetCheck.parse("0|2|9|40"))
                .isEqualTo(new BudgetCheck(false, BudgetCheck.Reason.COST, 9, 40));
        assertThat(BudgetCheck.parse("1|0|0|0").allowed()).isTrue();
    }

    private static ChatCompletionRequest request(String content, Integer maxTokens) {
        return new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new ChatCompletionRequest.ChatMessage("user", new TextNode(content), null, null, null)),
                null,
                null,
                null,
                null,
                null,
                null,
                maxTokens,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
