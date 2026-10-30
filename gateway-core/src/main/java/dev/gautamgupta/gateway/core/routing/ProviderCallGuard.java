package dev.gautamgupta.gateway.core.routing;

import dev.gautamgupta.gateway.core.provider.ProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry.RetrySignal;

/**
 * Decorator order per target, outermost first (ADR-0005): time limiter, circuit
 * breaker, retry, call.
 *
 * <p>Resilience4j records one breaker result for that whole subscription, so the
 * retries inside it do not each increment the failure count. A retry is another
 * attempt at the same call, and the breaker sees the outcome after they finish.
 * Streaming retries only while no chunk has been emitted; a later failure ends
 * the stream instead of replaying it.
 */
public final class ProviderCallGuard {

    private final CircuitBreakerRegistry circuitBreakers;
    private final CircuitBreakerConfig circuitBreakerConfig;

    public ProviderCallGuard(CircuitBreakerSettings settings) {
        this.circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(ProviderFailures::countsTowardBreaker)
                .build();
        this.circuitBreakers = CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    public CircuitBreakerRegistry circuitBreakers() {
        return circuitBreakers;
    }

    public <T> Mono<T> guard(String providerId, String model, Duration timeout, RetrySettings retry, Mono<T> call) {
        CircuitBreaker circuitBreaker = breaker(providerId, model);
        Retry resilienceRetry = retry(providerId, model, retry);
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(timeout).build());
        return call
                .transformDeferred(source -> Mono.from(RetryOperator.<T>of(resilienceRetry).apply(source)))
                .transformDeferred(source -> Mono.from(CircuitBreakerOperator.<T>of(circuitBreaker).apply(source)))
                .transformDeferred(source -> Mono.from(TimeLimiterOperator.<T>of(timeLimiter).apply(source)))
                .onErrorMap(error -> mapDeadline(providerId, error));
    }

    public <T> Flux<T> guardStream(
            String providerId,
            String model,
            Duration timeout,
            RetrySettings retry,
            Flux<T> call
    ) {
        CircuitBreaker circuitBreaker = breaker(providerId, model);
        Retry resilienceRetry = retry(providerId, model, retry);
        Flux<T> retried = retryBeforeFirstChunk(call, resilienceRetry);
        return retried
                .transformDeferred(source -> Flux.from(CircuitBreakerOperator.<T>of(circuitBreaker).apply(source)))
                .timeout(timeout, ignored -> Mono.never())
                .onErrorMap(error -> mapDeadline(providerId, error));
    }

    private CircuitBreaker breaker(String providerId, String model) {
        return circuitBreakers.circuitBreaker(providerId + ":" + model, circuitBreakerConfig);
    }

    private static Retry retry(String providerId, String model, RetrySettings settings) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(settings.backoff(), settings.multiplier()))
                .retryOnException(ProviderFailures::retryable)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of(providerId + ":" + model, config);
    }

    /**
     * Resilience4j's Flux retry replays the source after a mid-stream error, which would
     * duplicate chunks already sent. The same {@link Retry} policy is applied, but only
     * while the attempt has not emitted a chunk.
     */
    private static <T> Flux<T> retryBeforeFirstChunk(Flux<T> call, Retry resilienceRetry) {
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            Retry.AsyncContext<T> context = resilienceRetry.asyncContext();
            return call
                    .doOnNext(ignored -> emitted.set(true))
                    .retryWhen(reactor.util.retry.Retry.from(signals -> signals.flatMap(signal ->
                            nextStreamAttempt(context, emitted, signal))))
                    .doOnComplete(context::onComplete);
        });
    }

    private static Mono<Long> nextStreamAttempt(
            Retry.AsyncContext<?> context,
            AtomicBoolean emitted,
            RetrySignal signal
    ) {
        if (emitted.get()) {
            return Mono.error(signal.failure());
        }
        long waitMillis = context.onError(signal.failure());
        if (waitMillis < 0) {
            return Mono.error(signal.failure());
        }
        return Mono.delay(Duration.ofMillis(waitMillis));
    }

    private static Throwable mapDeadline(String providerId, Throwable error) {
        if (error instanceof ProviderException) {
            return error;
        }
        if (error instanceof CallNotPermittedException || hasCause(error, CallNotPermittedException.class)) {
            return ProviderException.circuitOpen(providerId);
        }
        if (error instanceof TimeoutException || hasCause(error, TimeoutException.class)) {
            return ProviderException.timeout(providerId, error);
        }
        return error;
    }

    private static boolean hasCause(Throwable error, Class<?> type) {
        Throwable cursor = error.getCause();
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
