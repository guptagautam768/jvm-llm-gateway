package dev.gautamgupta.gateway.tenancy.limit;

import reactor.core.publisher.Mono;

/** Atomic token bucket. {@code tokens} is how many to take (1 for a request, or an estimate for TPM). */
public interface RequestLimiter {

    Mono<RateLimitDecision> consume(String bucketKey, double capacity, double refillPerSecond, long tokens);
}
