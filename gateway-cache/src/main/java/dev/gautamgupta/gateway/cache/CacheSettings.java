package dev.gautamgupta.gateway.cache;

import java.time.Duration;

/** Index and retention settings. The per-request threshold lives on {@link CacheQuery}. */
public record CacheSettings(
        Duration exactTtl,
        int exactMaxEntries,
        boolean semanticEnabled,
        Duration semanticTtl,
        int semanticMaxEntries,
        int dimensions
) {

    public CacheSettings {
        if (exactTtl == null || exactTtl.isZero() || exactTtl.isNegative()) {
            throw new IllegalArgumentException("exact ttl must be positive");
        }
        if (semanticTtl == null || semanticTtl.isZero() || semanticTtl.isNegative()) {
            throw new IllegalArgumentException("semantic ttl must be positive");
        }
        if (exactMaxEntries < 1 || semanticMaxEntries < 1) {
            throw new IllegalArgumentException("cache max entries must be positive");
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("embedding dimensions must be positive");
        }
    }
}
