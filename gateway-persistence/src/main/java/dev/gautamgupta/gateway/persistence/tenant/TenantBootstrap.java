package dev.gautamgupta.gateway.persistence.tenant;

import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyHasher;
import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyToken;
import java.nio.ByteBuffer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Upserts tenants from configuration. The plaintext secret is hashed with SHA-256 and
 * is never written to Postgres or to the log (CFG-005, NFR-003).
 */
public final class TenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final DatabaseClient database;
    private final List<TenantSeed> seeds;

    public TenantBootstrap(DatabaseClient database, List<TenantSeed> seeds) {
        this.database = database;
        this.seeds = List.copyOf(seeds);
    }

    public Mono<Void> seed() {
        return Flux.fromIterable(seeds).concatMap(this::upsert).then();
    }

    private Mono<Void> upsert(TenantSeed seed) {
        return upsertTenant(seed)
                .then(upsertLimits(seed))
                .then(upsertKey(seed));
    }

    private Mono<Void> upsertTenant(TenantSeed seed) {
        String status = seed.status() == dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord.TenantStatus.DISABLED
                ? "disabled"
                : "active";
        return database.sql("""
                        INSERT INTO tenant (id, display_name, status)
                        VALUES (:id, :display_name, :status)
                        ON CONFLICT (id) DO UPDATE
                            SET display_name = EXCLUDED.display_name,
                                status = EXCLUDED.status,
                                updated_at = now()
                        """)
                .bind("id", seed.tenantId())
                .bind("display_name", seed.displayName())
                .bind("status", status)
                .then();
    }

    private Mono<Void> upsertLimits(TenantSeed seed) {
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                        INSERT INTO tenant_limits (
                            tenant_id, rate_capacity, rate_refill_per_second, tokens_per_minute,
                            token_budget, cost_budget_micros, budget_window_seconds
                        ) VALUES (
                            :tenant_id, :rate_capacity, :rate_refill_per_second, :tokens_per_minute,
                            :token_budget, :cost_budget_micros, :budget_window_seconds
                        )
                        ON CONFLICT (tenant_id) DO UPDATE
                            SET rate_capacity = EXCLUDED.rate_capacity,
                                rate_refill_per_second = EXCLUDED.rate_refill_per_second,
                                tokens_per_minute = EXCLUDED.tokens_per_minute,
                                token_budget = EXCLUDED.token_budget,
                                cost_budget_micros = EXCLUDED.cost_budget_micros,
                                budget_window_seconds = EXCLUDED.budget_window_seconds,
                                updated_at = now()
                        """)
                .bind("tenant_id", seed.tenantId())
                .bind("rate_capacity", seed.rateCapacity())
                .bind("rate_refill_per_second", seed.rateRefillPerSecond())
                .bind("budget_window_seconds", seed.budgetWindow().getSeconds());
        spec = bindLong(spec, "tokens_per_minute", seed.tokensPerMinute());
        spec = bindLong(spec, "token_budget", seed.tokenBudget());
        spec = bindLong(spec, "cost_budget_micros", seed.costBudgetMicros());
        return spec.then();
    }

    private Mono<Void> upsertKey(TenantSeed seed) {
        if (seed.keyId() == null || seed.keyId().isBlank()) {
            if (seed.secret() != null && !seed.secret().isBlank()) {
                return Mono.error(new IllegalStateException(
                        "bootstrap tenant " + seed.tenantId() + " has a secret without a key id"));
            }
            log.info("bootstrap tenant={} has no api key", seed.tenantId());
            return Mono.empty();
        }
        if (seed.secret() == null || seed.secret().isBlank()) {
            log.warn("bootstrap tenant={} keyId={} has no secret; key was not stored", seed.tenantId(), seed.keyId());
            return Mono.empty();
        }
        try {
            ApiKeyToken.parse("lgw_" + seed.keyId() + "_" + seed.secret());
        } catch (IllegalArgumentException ex) {
            return Mono.error(new IllegalStateException(
                    "bootstrap key " + seed.keyId() + " for tenant " + seed.tenantId() + " is not a valid gateway key",
                    ex));
        }
        byte[] hash = ApiKeyHasher.hash(seed.secret());
        log.info("bootstrap tenant={} keyId={}", seed.tenantId(), seed.keyId());
        return database.sql("""
                        INSERT INTO api_key (key_id, tenant_id, secret_hash, status, revoked_at)
                        VALUES (:key_id, :tenant_id, :secret_hash, 'active', NULL)
                        ON CONFLICT (key_id) DO UPDATE
                            SET tenant_id = EXCLUDED.tenant_id,
                                secret_hash = EXCLUDED.secret_hash,
                                status = 'active',
                                revoked_at = NULL
                        """)
                .bind("key_id", seed.keyId())
                .bind("tenant_id", seed.tenantId())
                .bind("secret_hash", ByteBuffer.wrap(hash))
                .then();
    }

    private static DatabaseClient.GenericExecuteSpec bindLong(
            DatabaseClient.GenericExecuteSpec spec,
            String name,
            Long value
    ) {
        if (value == null) {
            return spec.bindNull(name, Long.class);
        }
        return spec.bind(name, value);
    }
}
