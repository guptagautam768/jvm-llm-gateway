package dev.gautamgupta.gateway.tenancy.limit;

import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

/**
 * Redis Lua token bucket (ADR-0007). One key holds the remaining tokens and the last
 * refill timestamp. The script returns {@code allowed|remaining|retryMs|limit|resetMs}.
 * A Redis failure becomes 503 (fail-closed, as ADR-0007 proposes).
 */
public final class RedisTokenBucket implements RequestLimiter {

    static final String SCRIPT = """
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cost = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])
            if cost > capacity then
              local retry = 60000
              if refill_per_ms > 0 then
                retry = math.ceil(cost / refill_per_ms)
              end
              return string.format('0|0|%d|%d|%d', math.floor(retry), math.floor(capacity), math.floor(now + retry))
            end
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local ts = tonumber(redis.call('HGET', KEYS[1], 'ts'))
            if tokens == nil then
              tokens = capacity
              ts = now
            else
              local elapsed = now - ts
              if elapsed < 0 then elapsed = 0 end
              if refill_per_ms > 0 then
                tokens = math.min(capacity, tokens + (elapsed * refill_per_ms))
              end
            end
            local allowed = 0
            local retry = 0
            if tokens >= cost then
              tokens = tokens - cost
              allowed = 1
            else
              local deficit = cost - tokens
              if refill_per_ms <= 0 then
                retry = 60000
              else
                retry = math.ceil(deficit / refill_per_ms)
              end
            end
            redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', string.format('%d', math.floor(now)))
            redis.call('PEXPIRE', KEYS[1], ttl)
            local remaining = math.floor(tokens)
            if remaining < 0 then remaining = 0 end
            local reset_ms = now
            if tokens < capacity and refill_per_ms > 0 then
              reset_ms = now + math.ceil((capacity - tokens) / refill_per_ms)
            end
            return string.format('%d|%d|%d|%d|%d', allowed, remaining, math.floor(retry), math.floor(capacity), math.floor(reset_ms))
            """;

    private static final long TTL_MS = 86_400_000L;

    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<String> script;

    public RedisTokenBucket(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(SCRIPT, String.class);
    }

    @Override
    public Mono<RateLimitDecision> consume(String bucketKey, double capacity, double refillPerSecond, long tokens) {
        if (tokens <= 0) {
            return Mono.error(new IllegalArgumentException("token cost must be positive"));
        }
        long now = System.currentTimeMillis();
        double refillPerMs = refillPerSecond / 1000.0;
        return redis.execute(
                        script,
                        java.util.List.of(bucketKey),
                        java.util.List.of(
                                Double.toString(capacity),
                                Double.toString(refillPerMs),
                                Long.toString(now),
                                Long.toString(tokens),
                                Long.toString(TTL_MS)))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("rate limit script returned nothing")))
                .map(RateLimitDecision::parse)
                .onErrorMap(ex -> ex instanceof TenancyException tenancy
                        ? tenancy
                        : TenancyException.dependency(ex));
    }
}
