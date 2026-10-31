package dev.gautamgupta.gateway.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.gautamgupta.gateway.api.Usage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {

    @Test
    void tenantTagIsOptIn() {
        SimpleMeterRegistry tagged = new SimpleMeterRegistry();
        GatewayMetrics withTenant = new GatewayMetrics(tagged, true);
        withTenant.recordRejection("token_budget_exceeded", "gpt-4o-mini", "acme");
        withTenant.recordEstimatedCost("gpt-4o-mini", 66, "acme");
        withTenant.recordPriceMissing("gpt-test-unpriced", "acme");

        assertThat(tagged.get("gateway.ratelimit.rejections")
                .tag("reason", "token_budget_exceeded")
                .tag("tenant", "acme")
                .counter()
                .count()).isEqualTo(1);
        assertThat(tagged.get("gateway.cost.estimated").tag("model", "gpt-4o-mini").counter().count())
                .isEqualTo(0.000066);
        assertThat(tagged.get("gateway.price.missing").tag("model", "gpt-test-unpriced").counter().count()).isEqualTo(1);

        SimpleMeterRegistry plain = new SimpleMeterRegistry();
        GatewayMetrics withoutTenant = new GatewayMetrics(plain, false);
        withoutTenant.recordSuccess(
                "chat.completions", "openai", "gpt-4o-mini", new Usage(2, 3, 5), System.nanoTime(), "acme");
        assertThat(plain.get("gateway.requests").meter().getId().getTag("tenant")).isNull();
        assertThat(plain.get("gateway.tokens").tag("type", "prompt").counter().count()).isEqualTo(2);
    }

    @Test
    void ledgerWriteResultsAreSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayMetrics metrics = new GatewayMetrics(registry, false);
        metrics.recordCache("hit", "exact");
        metrics.recordCache("miss", "semantic");
        metrics.recordCacheHit("chat.completions", "openai", "gpt-4o-mini", new Usage(5, 7, 12), null);
        assertThat(registry.get("gateway.cache").tag("result", "hit").tag("type", "exact").counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.cache").tag("result", "miss").tag("type", "semantic").counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.requests").tag("status", "200").counter().count()).isEqualTo(1);
        assertThat(registry.find("gateway.provider.latency").timers()).isEmpty();
        metrics.recordLedgerWrite(true);
        metrics.recordLedgerWrite(false);
        assertThat(registry.get("gateway.ledger.writes").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway.ledger.writes").tag("result", "failure").counter().count()).isEqualTo(1);
    }
}
