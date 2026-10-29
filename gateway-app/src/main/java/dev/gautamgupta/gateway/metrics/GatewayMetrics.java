package dev.gautamgupta.gateway.metrics;

import dev.gautamgupta.gateway.api.Usage;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Week-1 slice of the metric names in ADR-0010.
 * Cache, rate-limit, PII, and cost series are added with the features that produce them.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry meterRegistry;

    public GatewayMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSuccess(String route, String provider, String model, Usage usage, long startNanos) {
        record(route, provider, model, "200", startNanos);
        recordTokens(provider, model, usage);
    }

    public void recordError(String route, String provider, String model, Throwable error, long startNanos) {
        record(route, provider, model, statusOf(error), startNanos);
    }

    private void record(String route, String provider, String model, String status, long startNanos) {
        String modelTag = model == null || model.isBlank() ? "none" : model;
        Counter.builder("gateway.requests")
                .description("Gateway API requests")
                .tags("route", route, "provider", provider, "model", modelTag, "status", status)
                .register(meterRegistry)
                .increment();
        Timer.builder("gateway.provider.latency")
                .description("Time spent in the provider call")
                .tags("provider", provider, "operation", route)
                .register(meterRegistry)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startNanos)));
    }

    private void recordTokens(String provider, String model, Usage usage) {
        if (usage == null) {
            return;
        }
        String modelTag = model == null || model.isBlank() ? "none" : model;
        increment("prompt", usage.promptTokens(), provider, modelTag);
        increment("completion", usage.completionTokens(), provider, modelTag);
    }

    private void increment(String type, Integer tokens, String provider, String model) {
        if (tokens == null || tokens <= 0) {
            return;
        }
        Counter.builder("gateway.tokens")
                .description("Tokens reported by the provider")
                .tags("type", type, "provider", provider, "model", model)
                .register(meterRegistry)
                .increment(tokens);
    }

    private static String statusOf(Throwable error) {
        if (error instanceof ProviderException providerException) {
            return Integer.toString(providerException.status());
        }
        if (error instanceof InvalidRequestException) {
            return "400";
        }
        return "500";
    }
}
