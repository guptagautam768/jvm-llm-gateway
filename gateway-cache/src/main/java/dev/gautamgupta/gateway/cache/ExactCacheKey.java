package dev.gautamgupta.gateway.cache;

/** Redis key shapes from ADR-0006. The tenant is outside the hash, so tenants cannot share an entry. */
public final class ExactCacheKey {

    public static final String EXACT_PREFIX = "cache:x:";

    public static final String SEMANTIC_PREFIX = "cache:s:";

    private ExactCacheKey() {
    }

    public static String redisKey(String tenantId, String exactHash) {
        return EXACT_PREFIX + tenantId + ":" + exactHash;
    }

    public static String semanticRedisKey(String tenantId, String exactHash) {
        return SEMANTIC_PREFIX + tenantId + ":" + exactHash;
    }
}
