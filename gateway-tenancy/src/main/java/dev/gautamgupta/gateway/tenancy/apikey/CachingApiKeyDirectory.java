package dev.gautamgupta.gateway.tenancy.apikey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Short-lived cache so Postgres stays off the hot path (ADR-0007). A revocation is
 * visible after the TTL. A zero TTL disables the cache.
 */
public final class CachingApiKeyDirectory implements ApiKeyDirectory {

    private static final Optional<ApiKeyRecord> ABSENT = Optional.empty();

    private final ApiKeyDirectory delegate;
    private final Cache<String, Optional<ApiKeyRecord>> cache;

    public CachingApiKeyDirectory(ApiKeyDirectory delegate, Duration ttl, long maximumSize) {
        this.delegate = delegate;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            this.cache = null;
        } else {
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(Math.max(1, maximumSize))
                    .build();
        }
    }

    @Override
    public Mono<ApiKeyRecord> find(String keyId) {
        if (cache == null) {
            return delegate.find(keyId);
        }
        Optional<ApiKeyRecord> cached = cache.getIfPresent(keyId);
        if (cached != null) {
            return cached.map(Mono::just).orElseGet(Mono::empty);
        }
        return delegate.find(keyId)
                .doOnNext(record -> cache.put(keyId, Optional.of(record)))
                .switchIfEmpty(Mono.defer(() -> {
                    cache.put(keyId, ABSENT);
                    return Mono.empty();
                }));
    }
}
