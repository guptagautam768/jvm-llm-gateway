package dev.gautamgupta.gateway.persistence.ledger;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

public final class R2dbcUsageLedgerWriter {

    private static final String INSERT = """
            INSERT INTO usage_ledger (
                id, ts, tenant_id, request_id, route, provider, model,
                prompt_tokens, completion_tokens, est_cost_micros, cache,
                latency_ms, status, error_code
            ) VALUES (
                :id, :ts, :tenant_id, :request_id, :route, :provider, :model,
                :prompt_tokens, :completion_tokens, :est_cost_micros, :cache,
                :latency_ms, :status, :error_code
            )
            """;

    private final DatabaseClient database;

    public R2dbcUsageLedgerWriter(DatabaseClient database) {
        this.database = database;
    }

    public Mono<Void> insert(UsageLedgerEntry entry) {
        DatabaseClient.GenericExecuteSpec spec = database.sql(INSERT)
                .bind("id", entry.id() == null ? UUID.randomUUID() : entry.id())
                .bind("ts", entry.ts())
                .bind("tenant_id", entry.tenantId())
                .bind("request_id", entry.requestId())
                .bind("route", entry.route())
                .bind("cache", entry.cache() == null ? "none" : entry.cache())
                .bind("status", entry.status());
        spec = bindNullable(spec, "provider", entry.provider(), String.class);
        spec = bindNullable(spec, "model", entry.model(), String.class);
        spec = bindNullable(spec, "prompt_tokens", entry.promptTokens(), Integer.class);
        spec = bindNullable(spec, "completion_tokens", entry.completionTokens(), Integer.class);
        spec = bindNullable(spec, "est_cost_micros", entry.estCostMicros(), Long.class);
        spec = bindNullable(spec, "latency_ms", entry.latencyMs(), Integer.class);
        spec = bindNullable(spec, "error_code", entry.errorCode(), String.class);
        return spec.then();
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec,
            String name,
            T value,
            Class<T> type
    ) {
        if (value == null) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value);
    }
}
