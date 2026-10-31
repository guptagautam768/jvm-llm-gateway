package dev.gautamgupta.gateway.cache;

import reactor.core.publisher.Mono;

/** Tenant-scoped exact cache and, when ready, the semantic index (ADR-0006). */
public interface ResponseCache {

    Mono<CacheRead> read(CacheQuery query);

    Mono<Void> write(CacheQuery query, CachedCompletion completion, float[] embedding);

    /** True after the RediSearch index exists and semantic lookup is allowed to run. */
    default boolean semanticReady() {
        return false;
    }
}
