package dev.gautamgupta.gateway.config;

import dev.gautamgupta.gateway.core.routing.CircuitBreakerSettings;
import dev.gautamgupta.gateway.core.routing.RetrySettings;
import dev.gautamgupta.gateway.core.routing.RouteCacheSettings;
import dev.gautamgupta.gateway.core.routing.RouteDefinition;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import dev.gautamgupta.gateway.core.routing.RoutingPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * YAML aliases and the Resilience4j defaults (ADR-0005). Model names in the
 * shipped file are illustrative.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayRoutingProperties {

    private Map<String, Route> routes = new LinkedHashMap<>();

    @NestedConfigurationProperty
    private Resilience resilience = new Resilience();

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(routes);
    }

    public Resilience getResilience() {
        return resilience;
    }

    public void setResilience(Resilience resilience) {
        this.resilience = resilience == null ? new Resilience() : resilience;
    }

    public RoutingPolicy toPolicy() {
        RetrySettings defaultRetry = resilience.getRetry().toSettings();
        List<RouteDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, Route> entry : routes.entrySet()) {
            Route route = entry.getValue() == null ? new Route() : entry.getValue();
            Duration timeout = route.getTimeout() == null ? resilience.getTimeout() : route.getTimeout();
            RetrySettings retry = route.getRetry() == null ? defaultRetry : route.getRetry().toSettings();
            List<RouteTarget> targets = new ArrayList<>();
            for (Target target : route.getTargets()) {
                targets.add(new RouteTarget(target.getProvider(), target.getModel()));
            }
            definitions.add(new RouteDefinition(
                    entry.getKey(), targets, timeout, retry, route.getCache().toSettings()));
        }
        return new RoutingPolicy(
                definitions,
                resilience.getTimeout(),
                defaultRetry,
                resilience.getCircuitBreaker().toSettings(),
                Instant.now().getEpochSecond());
    }

    public static class Route {
        private List<Target> targets = new ArrayList<>();
        private Duration timeout;
        private Retry retry;
        @NestedConfigurationProperty
        private RouteCache cache = new RouteCache();

        public List<Target> getTargets() {
            return targets;
        }

        public void setTargets(List<Target> targets) {
            this.targets = targets == null ? new ArrayList<>() : targets;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }

        public RouteCache getCache() {
            if (cache == null) {
                cache = new RouteCache();
            }
            return cache;
        }

        public void setCache(RouteCache cache) {
            this.cache = cache == null ? new RouteCache() : cache;
        }
    }

    /** Optional per-alias cache overrides. Null fields inherit {@code gateway.cache}. */
    public static class RouteCache {
        private Boolean semantic;
        private Double threshold;

        public Boolean getSemantic() {
            return semantic;
        }

        public void setSemantic(Boolean semantic) {
            this.semantic = semantic;
        }

        public Double getThreshold() {
            return threshold;
        }

        public void setThreshold(Double threshold) {
            this.threshold = threshold;
        }

        public RouteCacheSettings toSettings() {
            return new RouteCacheSettings(semantic, threshold);
        }
    }

    public static class Target {
        private String provider = "";
        private String model = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Retry {
        private int maxAttempts = 2;
        private Duration backoff = Duration.ofMillis(200);
        private double multiplier = 2.0d;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public RetrySettings toSettings() {
            return new RetrySettings(maxAttempts, backoff, multiplier);
        }
    }

    public static class Resilience {
        private Duration timeout = Duration.ofSeconds(30);
        @NestedConfigurationProperty
        private Retry retry = new Retry();
        @NestedConfigurationProperty
        private CircuitBreaker circuitBreaker = new CircuitBreaker();

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry == null ? new Retry() : retry;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker == null ? new CircuitBreaker() : circuitBreaker;
        }
    }

    public static class CircuitBreaker {
        private float failureRateThreshold = 50;
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 10;
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }

        public CircuitBreakerSettings toSettings() {
            return new CircuitBreakerSettings(
                    failureRateThreshold,
                    slidingWindowSize,
                    minimumNumberOfCalls,
                    waitDurationInOpenState,
                    permittedNumberOfCallsInHalfOpenState);
        }
    }
}
