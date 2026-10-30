package dev.gautamgupta.gateway.tenancy.budget;

import dev.gautamgupta.gateway.tenancy.error.TenancyException;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;

/**
 * Counters at {@code budget:{tenant}:{window}} (ADR-0007). Pre-flight does not increment.
 * The post-call {@link #add} is the reconcile step. Redis errors are fail-closed on check
 * and are left to the caller on add.
 */
public final class RedisBudgetCounter implements BudgetCounter {

    static final String CHECK = """
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0')
            local cost = tonumber(redis.call('HGET', KEYS[1], 'cost_micros') or '0')
            local token_limit = tonumber(ARGV[1])
            local est_tokens = tonumber(ARGV[2])
            local enforce_cost = tonumber(ARGV[3])
            local cost_limit = tonumber(ARGV[4])
            local est_cost = tonumber(ARGV[5])
            if token_limit >= 0 and (tokens + est_tokens) > token_limit then
              return string.format('0|1|%d|%d', math.floor(tokens), math.floor(cost))
            end
            if enforce_cost == 1 and (cost + est_cost) > cost_limit then
              return string.format('0|2|%d|%d', math.floor(tokens), math.floor(cost))
            end
            return string.format('1|0|%d|%d', math.floor(tokens), math.floor(cost))
            """;

    static final String ADD = """
            local tokens = tonumber(ARGV[1])
            local cost = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            if tokens > 0 then
              redis.call('HINCRBY', KEYS[1], 'tokens', tokens)
            end
            if cost > 0 then
              redis.call('HINCRBY', KEYS[1], 'cost_micros', cost)
            end
            if tokens > 0 or cost > 0 then
              redis.call('PEXPIRE', KEYS[1], ttl)
            end
            return '1'
            """;

    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<String> checkScript;
    private final DefaultRedisScript<String> addScript;

    public RedisBudgetCounter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        this.checkScript = new DefaultRedisScript<>(CHECK, String.class);
        this.addScript = new DefaultRedisScript<>(ADD, String.class);
    }

    @Override
    public Mono<BudgetCheck> check(
            String key,
            long tokenLimit,
            long estimatedTokens,
            boolean enforceCost,
            long costLimit,
            long estimatedCostMicros
    ) {
        return redis.execute(
                        checkScript,
                        List.of(key),
                        List.of(
                                Long.toString(tokenLimit),
                                Long.toString(Math.max(0, estimatedTokens)),
                                enforceCost ? "1" : "0",
                                Long.toString(costLimit),
                                Long.toString(Math.max(0, estimatedCostMicros))))
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("budget script returned nothing")))
                .map(BudgetCheck::parse)
                .onErrorMap(ex -> ex instanceof TenancyException tenancy
                        ? tenancy
                        : TenancyException.dependency(ex));
    }

    @Override
    public Mono<Void> add(String key, long ttlMillis, long tokens, long costMicros) {
        if (tokens <= 0 && costMicros <= 0) {
            return Mono.empty();
        }
        return redis.execute(
                        addScript,
                        List.of(key),
                        List.of(Long.toString(Math.max(0, tokens)), Long.toString(Math.max(0, costMicros)), Long.toString(ttlMillis)))
                .then();
    }
}
