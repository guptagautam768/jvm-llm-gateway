package dev.gautamgupta.gateway.cache;

import java.util.List;
import reactor.core.publisher.Mono;

/** Used when {@code gateway.cache.enabled} is false. */
public final class NoopResponseCache implements ResponseCache {

    @Override
    public Mono<CacheRead> read(CacheQuery query) {
        return Mono.just(CacheRead.miss(List.of(), null));
    }

    @Override
    public Mono<Void> write(CacheQuery query, CachedCompletion completion, float[] embedding) {
        return Mono.empty();
    }
}
