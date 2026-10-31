package dev.gautamgupta.gateway.web;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.cache.CacheQuery;
import dev.gautamgupta.gateway.cache.CacheRead;
import dev.gautamgupta.gateway.cache.CachedCompletion;
import dev.gautamgupta.gateway.cache.ResponseCache;
import dev.gautamgupta.gateway.cache.SemanticEligibility;
import dev.gautamgupta.gateway.config.CacheProperties;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ResolvedRoute;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import dev.gautamgupta.gateway.guardrails.PromptRedactor;
import dev.gautamgupta.gateway.guardrails.RequestSizeLimit;
import dev.gautamgupta.gateway.guardrails.pii.PiiDetectedException;
import dev.gautamgupta.gateway.guardrails.pii.RedactionCounts;
import dev.gautamgupta.gateway.guardrails.pii.RedactionResult;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * ADR-0009 order for one chat request: size limit, then redaction, then the cache.
 *
 * <p>Streaming and {@code X-Cache-Bypass: true} skip lookup and write. Redaction still runs,
 * including for streams, and the provider is called with the redacted request.
 */
public final class ChatCacheService {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheService.class);

    private final CacheProperties properties;
    private final ModelRouter router;
    private final ResponseCache cache;
    private final PromptRedactor redactor;
    private final RequestSizeLimit sizeLimit;
    private final GatewayMetrics metrics;
    private final ObservationRegistry observations;

    public ChatCacheService(
            CacheProperties properties,
            ModelRouter router,
            ResponseCache cache,
            PromptRedactor redactor,
            RequestSizeLimit sizeLimit
    ) {
        this(properties, router, cache, redactor, sizeLimit, null, ObservationRegistry.NOOP);
    }

    public ChatCacheService(
            CacheProperties properties,
            ModelRouter router,
            ResponseCache cache,
            PromptRedactor redactor,
            RequestSizeLimit sizeLimit,
            GatewayMetrics metrics,
            ObservationRegistry observations
    ) {
        this.properties = properties;
        this.router = router;
        this.cache = cache;
        this.redactor = redactor;
        this.sizeLimit = sizeLimit;
        this.metrics = metrics;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
    }

    public Mono<CacheDecision> lookup(String tenantId, ChatCompletionRequest request, CacheMode mode) {
        sizeLimit.check(request);
        RedactionResult redaction = redact(request, tenantId);
        if (metrics != null) {
            metrics.recordPiiRedactions(redaction.counts(), tenantId);
        }
        if (!redaction.counts().isEmpty()) {
            log.info("pii redactions tenant={} {}", tenantId == null || tenantId.isBlank() ? "none" : tenantId,
                    RedactionCounts.format(redaction.counts()));
        }
        if (redaction.rejected()) {
            throw new PiiDetectedException(redaction.counts());
        }
        ChatCompletionRequest redacted = redaction.request();
        if (!properties.isEnabled() || mode != CacheMode.USE || tenantId == null || tenantId.isBlank()) {
            return Mono.just(new CacheDecision(redacted, null, null, skipHeader(mode)));
        }
        CacheQuery query = query(tenantId, redacted);
        return Spans.mono(observations, "gateway.cache", cache.read(query))
                .map(read -> new CacheDecision(redacted, query, read, read.hit() != null ? read.hitType() : "miss"))
                .onErrorResume(error -> {
                    log.warn("cache lookup failed ({})", error.getClass().getSimpleName());
                    return Mono.just(new CacheDecision(
                            redacted,
                            query,
                            CacheRead.miss(List.of(), null),
                            "miss"));
                });
    }

    private RedactionResult redact(ChatCompletionRequest request, String tenantId) {
        Observation observation = Spans.start(observations, "gateway.redaction");
        try {
            return redactor.apply(request, tenantId);
        } catch (RuntimeException ex) {
            observation.error(ex);
            throw ex;
        } finally {
            observation.stop();
        }
    }

    public Mono<Void> store(CacheDecision decision, CachedCompletion completion) {
        if (decision.query() == null || decision.hit() != null) {
            return Mono.empty();
        }
        float[] embedding = decision.read() == null ? null : decision.read().embedding();
        return cache.write(decision.query(), completion, embedding);
    }

    private String skipHeader(CacheMode mode) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (mode == CacheMode.BYPASS) {
            return "bypass";
        }
        if (mode == CacheMode.STREAM) {
            return "skip";
        }
        return null;
    }

    private CacheQuery query(String tenantId, ChatCompletionRequest request) {
        ResolvedRoute route = router.resolve(request.model());
        RouteTarget primary = route.targets().get(0);
        boolean semantic = properties.getSemantic().isEnabled()
                && cache.semanticReady()
                && SemanticEligibility.allowed(request.temperature(), properties.getSemantic().getMaxTemperature());
        if (Boolean.FALSE.equals(route.cacheSettings().semanticEnabled())) {
            semantic = false;
        }
        double threshold = properties.getSemantic().getThreshold();
        if (route.cacheSettings().threshold() != null) {
            threshold = route.cacheSettings().threshold();
        }
        return new CacheQuery(
                tenantId,
                request.model(),
                primary.provider(),
                primary.model(),
                request,
                semantic,
                threshold);
    }
}
