package dev.gautamgupta.gateway.web;

import dev.gautamgupta.gateway.api.chat.ChatCompletionRequest;
import dev.gautamgupta.gateway.cache.CacheObservation;
import dev.gautamgupta.gateway.cache.CacheQuery;
import dev.gautamgupta.gateway.cache.CacheRead;
import dev.gautamgupta.gateway.cache.CachedCompletion;
import java.util.List;

/** Redacted request plus the cache lookup, if one ran. */
public record CacheDecision(
        ChatCompletionRequest redacted,
        CacheQuery query,
        CacheRead read,
        String header
) {

    public CachedCompletion hit() {
        return read == null ? null : read.hit();
    }

    public String hitType() {
        return read == null ? null : read.hitType();
    }

    public List<CacheObservation> observations() {
        return read == null ? List.of() : read.observations();
    }
}
