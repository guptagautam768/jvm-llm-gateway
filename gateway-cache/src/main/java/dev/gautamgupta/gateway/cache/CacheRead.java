package dev.gautamgupta.gateway.cache;

import java.util.List;

/**
 * Lookup outcome. {@code embedding} is set on a semantic miss so the following write
 * can index the vector without embedding twice. It is null when semantic lookup did not run.
 */
public record CacheRead(
        CachedCompletion hit,
        String hitType,
        List<CacheObservation> observations,
        float[] embedding
) {

    public CacheRead {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public static CacheRead exactHit(CachedCompletion hit) {
        return new CacheRead(hit, "exact", List.of(new CacheObservation("hit", "exact")), null);
    }

    public static CacheRead semanticHit(CachedCompletion hit) {
        return new CacheRead(
                hit,
                "semantic",
                List.of(new CacheObservation("miss", "exact"), new CacheObservation("hit", "semantic")),
                null);
    }

    public static CacheRead miss(List<CacheObservation> observations, float[] embedding) {
        return new CacheRead(null, null, observations, embedding);
    }
}
