# ADR-0008: Persistence: Postgres + Flyway for tenants and usage ledger, 30-day retention

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

DATA-001 (tenants, key hashes) and DATA-002 (usage ledger) need durable storage; the ledger is retained **30 days** (RD-007) and must support per-tenant cost/token queries (NFR-005, FR-070/071). No raw prompts are stored (NFR-004, DATA-005).

## Decision drivers

- FR-070, FR-071, NFR-004, NFR-005, DATA-001, DATA-002, RD-007, AC-012, ADR-0003 (reactive stack).

## Considered options

1. **PostgreSQL 16 + Flyway migrations; R2DBC at runtime.**
2. Ledger in Redis streams / time-series.
3. ClickHouse/TimescaleDB for ledger.

## Decision outcome

**Chosen: option 1.**

- Tables: `tenant`, `api_key` (`key_id`, `secret_hash`, `status`, `created_at`, `revoked_at`), `tenant_limits`, `model_price` (optional; price table may stay in YAML per FR-071), `usage_ledger`.
- `usage_ledger`: `id, ts, tenant_id, request_id, route, provider, model, prompt_tokens, completion_tokens, est_cost_micros (nullable), cache (none|exact|semantic), latency_ms, status, error_code`. **Range-partitioned by day** on `ts`.
- **Retention (RD-007):** a scheduled job (ShedLock-guarded for multi-instance) drops partitions older than 30 days (configurable `gateway.ledger.retention=30d`) and pre-creates upcoming partitions. Dropping partitions avoids large `DELETE`/vacuum cost.
- Ledger writes are async (non-blocking, buffered) so they don't add to client latency; a failed write is logged + metered, not surfaced to the client.
- Flyway runs via JDBC at startup; runtime uses R2DBC.

## Consequences

- ➕ Postgres is ubiquitous, SQL-queryable for cost reports, Testcontainers-friendly.
- ➕ Partition drop makes retention cheap and predictable.
- ➖ Async writes can lose ledger rows on crash; accepted for MVP (not invoice-grade, per §10.1).
- ➖ Native partition management adds complexity vs. a single table + `DELETE` — could start simple and switch (reviewer call).
- ➖ Two drivers (R2DBC + JDBC) as noted in ADR-0003.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
