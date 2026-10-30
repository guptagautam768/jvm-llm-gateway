package dev.gautamgupta.gateway.tenancy.budget;

import reactor.core.publisher.Mono;

/**
 * Read-only pre-flight check plus a later increment. A negative token limit skips that
 * check. Cost is enforced only when {@code enforceCost} is true.
 */
public interface BudgetCounter {

    Mono<BudgetCheck> check(
            String key,
            long tokenLimit,
            long estimatedTokens,
            boolean enforceCost,
            long costLimit,
            long estimatedCostMicros);

    Mono<Void> add(String key, long ttlMillis, long tokens, long costMicros);
}
