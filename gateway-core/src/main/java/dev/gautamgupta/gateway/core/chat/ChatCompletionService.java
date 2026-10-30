package dev.gautamgupta.gateway.core.chat;

import dev.gautamgupta.gateway.api.chat.ChatCompletionChunk;
import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.api.chat.ChatCompletionResponse;
import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.provider.InvalidRequestException;
import dev.gautamgupta.gateway.core.provider.ProviderClient;
import dev.gautamgupta.gateway.core.provider.ProviderException;
import dev.gautamgupta.gateway.core.provider.ProviderRegistry;
import dev.gautamgupta.gateway.core.routing.ModelRouter;
import dev.gautamgupta.gateway.core.routing.ProviderCallGuard;
import dev.gautamgupta.gateway.core.routing.ProviderFailures;
import dev.gautamgupta.gateway.core.routing.ResolvedRoute;
import dev.gautamgupta.gateway.core.routing.RouteTarget;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validates the chat request, resolves the model alias, and runs the ordered
 * fallback chain. Each target is guarded by a time limiter, circuit breaker, and
 * retry (ADR-0005). The response {@code model} is the name the client sent.
 */
public final class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);

    private final ProviderRegistry registry;
    private final ModelRouter router;
    private final ProviderCallGuard guard;

    public ChatCompletionService(
            ProviderRegistry registry,
            ModelRouter router,
            ProviderCallGuard guard
    ) {
        this.registry = registry;
        this.router = router;
        this.guard = guard;
    }

    public void validate(ChatCompletionRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body is required.", null);
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new InvalidRequestException("model is required.", "model");
        }
        if (request.messages().isEmpty()) {
            throw new InvalidRequestException("messages is required.", "messages");
        }
        for (int i = 0; i < request.messages().size(); i++) {
            ChatCompletionRequest.ChatMessage message = request.messages().get(i);
            if (message == null || message.role() == null || message.role().isBlank()) {
                throw new InvalidRequestException("message role is required.", "messages[" + i + "].role");
            }
        }
        router.resolve(request.model());
    }

    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        return complete(request, new AttemptTrace());
    }

    public Mono<ChatCompletionResponse> complete(ChatCompletionRequest request, AttemptTrace trace) {
        ResolvedRoute route = router.resolve(request.model());
        return attemptComplete(route, request, 0, null, trace);
    }

    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        return stream(request, new AttemptTrace());
    }

    public Flux<ChatCompletionChunk> stream(ChatCompletionRequest request, AttemptTrace trace) {
        ResolvedRoute route = router.resolve(request.model());
        return attemptStream(route, request, 0, null, trace);
    }

    public Mono<ModelListResponse> listModels() {
        return Mono.just(router.catalog());
    }

    private Mono<ChatCompletionResponse> attemptComplete(
            ResolvedRoute route,
            ChatCompletionRequest request,
            int index,
            Throwable previous,
            AttemptTrace trace
    ) {
        if (index >= route.targets().size()) {
            return Mono.error(terminal(previous));
        }
        RouteTarget target = route.targets().get(index);
        ProviderClient client = registry.get(target.provider());
        ChatCompletionRequest upstream = request.withModel(target.model()).withStream(null);
        return Mono.defer(() -> {
            try {
                client.ensureConfigured();
            } catch (RuntimeException ex) {
                return Mono.error(ex);
            }
            trace.record(client.id(), target.model());
            return guard.guard(client.id(), target.model(), route.timeout(), route.retry(), client.complete(upstream))
                    .map(response -> rewrite(response, request.model()));
        }).onErrorResume(error -> continueAfter(route, request, index, previous, trace, error));
    }

    private Flux<ChatCompletionChunk> attemptStream(
            ResolvedRoute route,
            ChatCompletionRequest request,
            int index,
            Throwable previous,
            AttemptTrace trace
    ) {
        if (index >= route.targets().size()) {
            return Flux.error(terminal(previous));
        }
        RouteTarget target = route.targets().get(index);
        ProviderClient client = registry.get(target.provider());
        ChatCompletionRequest upstream = request.withModel(target.model()).withStream(true);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return Flux.defer(() -> {
            try {
                client.ensureConfigured();
            } catch (RuntimeException ex) {
                return Flux.error(ex);
            }
            trace.record(client.id(), target.model());
            return guard.guardStream(
                            client.id(), target.model(), route.timeout(), route.retry(), client.stream(upstream))
                    .doOnNext(ignored -> emitted.set(true))
                    .map(chunk -> rewrite(chunk, request.model()));
        }).onErrorResume(error -> {
            if (emitted.get()) {
                return Flux.error(error);
            }
            return continueStream(route, request, index, previous, trace, error);
        });
    }

    private Mono<ChatCompletionResponse> continueAfter(
            ResolvedRoute route,
            ChatCompletionRequest request,
            int index,
            Throwable previous,
            AttemptTrace trace,
            Throwable error
    ) {
        Throwable chosen = prefer(previous, error);
        if (ProviderFailures.fallbackEligible(error) && index + 1 < route.targets().size()) {
            RouteTarget next = route.targets().get(index + 1);
            log.warn(
                    "provider fallback from {}/{} to {}/{} status={}",
                    route.targets().get(index).provider(),
                    route.targets().get(index).model(),
                    next.provider(),
                    next.model(),
                    statusOf(error));
            return attemptComplete(route, request, index + 1, chosen, trace);
        }
        if (ProviderFailures.fallbackEligible(error)) {
            return Mono.error(terminal(chosen));
        }
        return Mono.error(error);
    }

    private Flux<ChatCompletionChunk> continueStream(
            ResolvedRoute route,
            ChatCompletionRequest request,
            int index,
            Throwable previous,
            AttemptTrace trace,
            Throwable error
    ) {
        Throwable chosen = prefer(previous, error);
        if (ProviderFailures.fallbackEligible(error) && index + 1 < route.targets().size()) {
            RouteTarget next = route.targets().get(index + 1);
            log.warn(
                    "provider stream fallback from {}/{} to {}/{} status={}",
                    route.targets().get(index).provider(),
                    route.targets().get(index).model(),
                    next.provider(),
                    next.model(),
                    statusOf(error));
            return attemptStream(route, request, index + 1, chosen, trace);
        }
        if (ProviderFailures.fallbackEligible(error)) {
            return Flux.error(terminal(chosen));
        }
        return Flux.error(error);
    }

    /**
     * A later "not configured" target should not hide an earlier timeout or 5xx.
     * Otherwise a route whose fallback has no API key would report 503 after the
     * primary already failed.
     */
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

    private static ChatCompletionResponse rewrite(ChatCompletionResponse response, String requestedModel) {
        return new ChatCompletionResponse(
                response.id(),
                response.object(),
                response.created(),
                requestedModel,
                response.choices(),
                response.usage(),
                response.systemFingerprint());
    }

    private static ChatCompletionChunk rewrite(ChatCompletionChunk chunk, String requestedModel) {
        return new ChatCompletionChunk(
                chunk.id(),
                chunk.object(),
                chunk.created(),
                requestedModel,
                chunk.choices(),
                chunk.usage(),
                chunk.systemFingerprint());
    }
}
