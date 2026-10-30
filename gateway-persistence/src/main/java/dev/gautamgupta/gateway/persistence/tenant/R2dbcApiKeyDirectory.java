package dev.gautamgupta.gateway.persistence.tenant;

import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyDirectory;
import dev.gautamgupta.gateway.tenancy.apikey.ApiKeyRecord;
import dev.gautamgupta.gateway.tenancy.apikey.TenantLimits;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

public final class R2dbcApiKeyDirectory implements ApiKeyDirectory {

    private static final String SELECT = """
            SELECT k.key_id,
                   k.secret_hash,
                   k.status AS key_status,
                   t.id AS tenant_id,
                   t.status AS tenant_status,
                   l.rate_capacity,
                   l.rate_refill_per_second,
                   l.tokens_per_minute,
                   l.token_budget,
                   l.cost_budget_micros,
                   l.budget_window_seconds
            FROM api_key k
            JOIN tenant t ON t.id = k.tenant_id
            LEFT JOIN tenant_limits l ON l.tenant_id = t.id
            WHERE k.key_id = :key_id
            """;

    private final DatabaseClient database;

    public R2dbcApiKeyDirectory(DatabaseClient database) {
        this.database = database;
    }

    @Override
    public Mono<ApiKeyRecord> find(String keyId) {
        return database.sql(SELECT)
                .bind("key_id", keyId)
                .map((row, metadata) -> toRecord(row))
                .one();
    }

    private static ApiKeyRecord toRecord(io.r2dbc.spi.Readable row) {
        Integer capacity = row.get("rate_capacity", Integer.class);
        Double refill = number(row.get("rate_refill_per_second"));
        Long windowSeconds = row.get("budget_window_seconds", Long.class);
        TenantLimits limits = null;
        if (capacity != null && refill != null && windowSeconds != null) {
            limits = new TenantLimits(
                    capacity,
                    refill,
                    row.get("tokens_per_minute", Long.class),
                    row.get("token_budget", Long.class),
                    row.get("cost_budget_micros", Long.class),
                    Duration.ofSeconds(windowSeconds));
        }
        return new ApiKeyRecord(
                row.get("key_id", String.class),
                row.get("tenant_id", String.class),
                bytes(row.get("secret_hash")),
                keyStatus(row.get("key_status", String.class)),
                tenantStatus(row.get("tenant_status", String.class)),
                limits,
                true);
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    private static byte[] bytes(Object value) {
        if (value instanceof byte[] direct) {
            return direct;
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer view = buffer.asReadOnlyBuffer();
            byte[] copy = new byte[view.remaining()];
            view.get(copy);
            return copy;
        }
        throw new IllegalStateException("secret_hash was not a byte array");
    }

    private static ApiKeyRecord.KeyStatus keyStatus(String status) {
        if ("active".equals(status)) {
            return ApiKeyRecord.KeyStatus.ACTIVE;
        }
        return ApiKeyRecord.KeyStatus.REVOKED;
    }

    private static ApiKeyRecord.TenantStatus tenantStatus(String status) {
        if ("active".equals(status)) {
            return ApiKeyRecord.TenantStatus.ACTIVE;
        }
        return ApiKeyRecord.TenantStatus.DISABLED;
    }
}
