package dev.gautamgupta.gateway.core.embed;

import dev.gautamgupta.gateway.api.embed.EmbeddingRequest;
import dev.gautamgupta.gateway.api.embed.EmbeddingResponse;
import dev.gautamgupta.gateway.core.chat.AttemptTrace;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ProviderCallGuard;
import dev.gautamgupta.gateway.core.routing.ProviderErrorKinds;
import dev.gautamgupta.gateway.core.routing.ProviderFailures;
import dev.gautamgupta.gateway.core.routing.ResolvedRoute;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Validates an embeddings request and walks the alias chain. Targets that cannot
 * embed are skipped (ADR-0015). Each capable target uses the same
 * time limiter, circuit breaker, and retry as chat (ADR-0005).
 */
public final class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ProviderRegistry registry;
    private final ModelRouter router;
    private final ProviderCallGuard guard;
    private final ObservationRegistry observations;

    public EmbeddingService(
            ProviderRegistry registry,
            ModelRouter router,
            ProviderCallGuard guard,
            ObservationRegistry observations
    ) {
        this.registry = registry;
        this.router = router;
        this.guard = guard;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
    }

    public void validate(EmbeddingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body is required.", null);
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new InvalidRequestException("model is required.", "model");
        }
        EmbeddingInputs.texts(request.input());
        if (request.dimensions() != null && request.dimensions() < 1) {
            throw new InvalidRequestException("dimensions must be positive.", "dimensions");
        }
        router.resolve(request.model());
    }

    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return embed(request, new AttemptTrace());
    }

    public Mono<EmbeddingResponse> embed(EmbeddingRequest request, AttemptTrace trace) {
        ResolvedRoute route = router.resolve(request.model());
        return attempt(route, request, 0, null, false, trace);
    }

    private Mono<EmbeddingResponse> attempt(
            ResolvedRoute route,
            EmbeddingRequest request,
            int index,
            Throwable previous,
            boolean attempted,
            AttemptTrace trace
    ) {
        int capable = nextCapable(route, index);
        if (capable < 0) {
            if (previous != null) {
                return Mono.error(terminal(previous));
            }
            if (!attempted) {
                return Mono.error(ProviderException.unsupported("gateway", "embeddings"));
            }
            return Mono.error(terminal(previous));
        }
        RouteTarget target = route.targets().get(capable);
        ProviderClient client = registry.get(target.provider());
        EmbeddingRequest upstream = request.withModel(target.model());
        return Mono.defer(() -> {
            Observation observation = startObservation(client.id(), target.model());
            try {
                client.ensureConfigured();
            } catch (RuntimeException ex) {
                observation.error(ex);
                observation.stop();
                return Mono.error(ex);
            }
            trace.record(client.id(), target.model());
            return guard.guard(client.id(), target.model(), route.timeout(), route.retry(), client.embed(upstream))
                    .map(response -> rewrite(response, request.model()))
                    .doOnError(observation::error)
                    .doFinally(signal -> observation.stop())
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        }).onErrorResume(error -> continueAfter(route, request, capable, previous, trace, error));
    }

    private Mono<EmbeddingResponse> continueAfter(
            ResolvedRoute route,
            EmbeddingRequest request,
            int index,
            Throwable previous,
            AttemptTrace trace,
            Throwable error
    ) {
        Throwable chosen = prefer(previous, error);
        String provider = route.targets().get(index).provider();
        trace.noteProviderError(provider, ProviderErrorKinds.of(error));
        int nextIndex = nextCapable(route, index + 1);
        if (ProviderFailures.fallbackEligible(error) && nextIndex >= 0) {
            RouteTarget next = route.targets().get(nextIndex);
            trace.noteFallback(provider, next.provider());
            log.warn(
                    "provider fallback from {}/{} to {}/{} status={}",
                    provider,
                    route.targets().get(index).model(),
                    next.provider(),
                    next.model(),
                    statusOf(error));
            return attempt(route, request, nextIndex, chosen, true, trace);
        }
        if (ProviderFailures.fallbackEligible(error)) {
            return Mono.error(terminal(chosen));
        }
        return Mono.error(error);
    }

    /**
     * A provider that cannot embed is not a failure. Chat aliases include Anthropic,
     * which has no embeddings API (ADR-0004).
     */
    private int nextCapable(ResolvedRoute route, int from) {
        for (int i = Math.max(0, from); i < route.targets().size(); i++) {
            RouteTarget target = route.targets().get(i);
            ProviderClient client = registry.get(target.provider());
            if (!client.capabilities().embeddings()) {
                log.info("skipping {}/{} for embeddings; provider cannot embed", target.provider(), target.model());
                continue;
            }
            return i;
        }
        return -1;
    }

    private static Throwable prefer(Throwable previous, Throwable current) {
        if (previous == null) {
            return current;
        }
        ProviderException currentFailure = ProviderFailures.find(current);
        ProviderException previousFailure = ProviderFailures.find(previous);
        if (currentFailure != null
                && currentFailure.kind() == ProviderException.Kind.NOT_CONFIGURED
                && previousFailure != null
                && previousFailure.kind() != ProviderException.Kind.NOT_CONFIGURED) {
            return previous;
        }
        return current;
    }

    static Throwable terminal(Throwable error) {
        if (error == null) {
            return ProviderException.exhausted("gateway");
        }
        ProviderException providerException = ProviderFailures.find(error);
        if (providerException == null) {
            if (ProviderFailures.isTimeout(error)) {
                return ProviderException.timeout("gateway", error);
            }
            return error;
        }
        return switch (providerException.kind()) {
            case TIMEOUT, CIRCUIT_OPEN, NOT_CONFIGURED, UNSUPPORTED -> providerException;
            case UPSTREAM -> {
                if (ProviderFailures.retryableStatus(providerException.status())) {
                    if (providerException.status() == 429) {
                        yield providerException;
                    }
                    yield ProviderException.exhausted(providerException.providerId());
                }
                yield providerException;
            }
            case UNAVAILABLE, MALFORMED -> ProviderException.exhausted(providerException.providerId());
        };
    }

    private Observation startObservation(String provider, String model) {
        return Observation.createNotStarted("gateway.provider", observations)
                .lowCardinalityKeyValue("provider", provider == null || provider.isBlank() ? "none" : provider)
                .lowCardinalityKeyValue("operation", "embed")
                .highCardinalityKeyValue("model", model == null || model.isBlank() ? "none" : model)
                .start();
    }

    private static int statusOf(Throwable error) {
        ProviderException providerException = ProviderFailures.find(error);
        if (providerException != null) {
            return providerException.status();
        }
        if (ProviderFailures.isTimeout(error)) {
            return 504;
        }
        return 502;
    }

    private static EmbeddingResponse rewrite(EmbeddingResponse response, String requestedModel) {
        if (response == null) {
            return new EmbeddingResponse("list", null, requestedModel, null);
        }
        String object = response.object() == null || response.object().isBlank() ? "list" : response.object();
        return new EmbeddingResponse(object, response.data(), requestedModel, response.usage());
    }
}
