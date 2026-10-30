-- Tenants, hashed API keys, limits, and the daily usage ledger (ADR-0007, ADR-0008).
-- The ledger stores no prompt or completion text (NFR-004).

CREATE TABLE tenant (
    id text PRIMARY KEY,
    display_name text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_status_chk CHECK (status IN ('active', 'disabled'))
);

CREATE TABLE api_key (
    key_id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenant (id),
    secret_hash bytea NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CONSTRAINT api_key_status_chk CHECK (status IN ('active', 'revoked')),
    CONSTRAINT api_key_hash_len_chk CHECK (octet_length(secret_hash) = 32)
);

CREATE INDEX api_key_tenant_idx ON api_key (tenant_id);

CREATE TABLE tenant_limits (
    tenant_id text PRIMARY KEY REFERENCES tenant (id),
    rate_capacity integer NOT NULL,
    rate_refill_per_second double precision NOT NULL,
    tokens_per_minute bigint,
    token_budget bigint,
    cost_budget_micros bigint,
    budget_window_seconds bigint NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_limits_rate_chk CHECK (rate_capacity > 0 AND rate_refill_per_second > 0),
    CONSTRAINT tenant_limits_window_chk CHECK (budget_window_seconds > 0)
);

CREATE TABLE usage_ledger (
    id uuid NOT NULL,
    ts timestamptz NOT NULL,
    tenant_id text NOT NULL,
    request_id text NOT NULL,
    route text NOT NULL,
    provider text,
    model text,
    prompt_tokens integer,
    completion_tokens integer,
    est_cost_micros bigint,
    cache text NOT NULL,
    latency_ms integer,
    status integer NOT NULL,
    error_code text,
    PRIMARY KEY (id, ts),
    CONSTRAINT usage_ledger_cache_chk CHECK (cache IN ('none', 'exact', 'semantic')),
    CONSTRAINT usage_ledger_status_chk CHECK (status BETWEEN 100 AND 599)
) PARTITION BY RANGE (ts);

CREATE INDEX usage_ledger_tenant_ts_idx ON usage_ledger (tenant_id, ts);

-- Partitions around "now" so the first requests succeed before the retention job runs.
DO $$
DECLARE
    d date;
BEGIN
    FOR d IN
        SELECT generate_series((current_date AT TIME ZONE 'UTC')::date - 1,
                               (current_date AT TIME ZONE 'UTC')::date + 7,
                               '1 day'::interval)::date
    LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF usage_ledger FOR VALUES FROM (%L) TO (%L)',
            'usage_ledger_p' || to_char(d, 'YYYYMMDD'),
            to_char(d, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(d + 1, 'YYYY-MM-DD') || ' 00:00:00+00');
    END LOOP;
END $$;

-- ShedLock (R2DBC provider). JDBC stays limited to Flyway (ADR-0003).
CREATE TABLE shedlock (
    name varchar(64) NOT NULL,
    lock_until timestamptz NOT NULL,
    locked_at timestamptz NOT NULL,
    locked_by varchar(255) NOT NULL,
    PRIMARY KEY (name)
);
