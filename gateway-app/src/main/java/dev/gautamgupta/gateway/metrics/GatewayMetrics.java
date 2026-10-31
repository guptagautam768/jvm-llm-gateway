package dev.gautamgupta.gateway.metrics;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Metric names from ADR-0010. The tenant tag is off unless
 * {@code gateway.tenancy.metrics-tenant-tag} is true.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;
    private final boolean tenantTagEnabled;

    public GatewayMetrics(
            MeterRegistry meterRegistry,
            @Value("${gateway.tenancy.metrics-tenant-tag:false}") boolean tenantTagEnabled
    ) {
        this.meterRegistry = meterRegistry;
        this.tenantTagEnabled = tenantTagEnabled;
    }

    public void recordSuccess(String route, String provider, String model, Usage usage, long startNanos, String tenantId) {
        record(route, provider, model, "200", startNanos, tenantId);
        recordTokens(provider, model, usage, tenantId);
    }

    public void recordError(String route, String provider, String model, Throwable error, long startNanos, String tenantId) {
        record(route, provider, model, statusOf(error), startNanos, tenantId);
    }

    /** A request that never reached a provider (auth, or a dependency failure). */
    public void recordAccess(String route, String status, String tenantId) {
        requestCounter(route, "none", "none", status, tenantId).increment();
    }

    public void recordRejection(String reason, String model, String tenantId) {
        Counter.Builder rejection = Counter.builder("gateway.ratelimit.rejections")
                .description("Rate limit and budget rejections")
                .tag("reason", reason);
        tagTenant(rejection, tenantId);
        rejection.register(meterRegistry).increment();
        requestCounter("chat.completions", "none", model, "429", tenantId).increment();
    }

    public void recordEstimatedCost(String model, long micros, String tenantId) {
        if (micros <= 0 || model == null || model.isBlank()) {
            return;
        }
        Counter.Builder cost = Counter.builder("gateway.cost.estimated")
                .description("Estimated spend in USD from the price table")
                .baseUnit("usd")
                .tag("model", model);
        tagTenant(cost, tenantId);
        cost.register(meterRegistry).increment(micros / 1_000_000.0);
    }

    public void recordPriceMissing(String model, String tenantId) {
        String modelTag = model == null || model.isBlank() ? "none" : model;
        Counter.Builder missing = Counter.builder("gateway.price.missing")
                .description("Priced calls whose model is absent from the price table")
                .tag("model", modelTag);
        tagTenant(missing, tenantId);
        missing.register(meterRegistry).increment();
    }

    /** A served response that did not call a provider. The provider latency timer stays unchanged. */
    public void recordCacheHit(String route, String provider, String model, Usage usage, String tenantId) {
        requestCounter(route, modelTag(provider), modelTag(model), "200", tenantId).increment();
        recordTokens(modelTag(provider), modelTag(model), usage, tenantId);
    }

    public void recordCache(String result, String type) {
        Counter.builder("gateway.cache")
                .description("Exact and semantic cache lookups")
                .tags("result", result, "type", type)
                .register(meterRegistry)
                .increment();
    }

    public void recordLedgerWrite(boolean success) {
        Counter.builder("gateway.ledger.writes")
                .description("Async usage ledger writes")
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }

    private void record(String route, String provider, String model, String status, long startNanos, String tenantId) {
        requestCounter(route, provider, model, status, tenantId).increment();
        Timer.builder("gateway.provider.latency")
                .description("Time spent in the provider call")
                .tags("provider", provider, "operation", route)
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos)));
    }

    private Counter requestCounter(String route, String provider, String model, String status, String tenantId) {
        Counter.Builder requests = Counter.builder("gateway.requests")
                .description("Gateway API requests")
                .tags(
                        "route", route,
                        "provider", provider,
                        "model", modelTag(model),
                        "status", status);
        tagTenant(requests, tenantId);
        return requests.register(meterRegistry);
    }

    private void recordTokens(String provider, String model, Usage usage, String tenantId) {
        if (usage == null) {
            return;
        }
        String modelTag = modelTag(model);
        increment("prompt", usage.promptTokens(), provider, modelTag, tenantId);
        increment("completion", usage.completionTokens(), provider, modelTag, tenantId);
    }

    private void increment(String type, Integer tokens, String provider, String model, String tenantId) {
        if (tokens == null || tokens <= 0) {
            return;
        }
        Counter.Builder counter = Counter.builder("gateway.tokens")
                .description("Tokens reported by the provider")
                .tags("type", type, "provider", provider, "model", model);
        tagTenant(counter, tenantId);
        counter.register(meterRegistry).increment(tokens);
    }

    private void tagTenant(Counter.Builder builder, String tenantId) {
        if (tenantTagEnabled && tenantId != null && !tenantId.isBlank()) {
            builder.tag("tenant", tenantId);
        }
    }

    private static String modelTag(String model) {
        return model == null || model.isBlank() ? "none" : model;
    }

    static String statusOf(Throwable error) {
        if (error instanceof TenancyException tenancy) {
            return Integer.toString(tenancy.httpStatus());
        }
        if (error instanceof ProviderException providerException) {
            return Integer.toString(providerException.status());
        }
        if (error instanceof InvalidRequestException) {
            return "400";
        }
        return "500";
    }
}
