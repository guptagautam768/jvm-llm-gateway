# ADR-0007: Tenancy and rate limiting: hashed API keys, Postgres tenants, Redis token bucket, budgets, 429 contract

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

Every request must map an API key to a tenant (FR-040), be rate limited via Redis (FR-041), respect token/$ budgets over a window (FR-042), and return 401/403/429 with stable codes (FR-043/044, API-030/031/034). Keys must never be stored in plaintext (NFR-003).

## Decision drivers

- G-006, FR-003, FR-040–044, NFR-003, NFR-009, API-030/031/034, AC-009.

## Considered options

- Key storage: **(a) SHA-256 of a high-entropy random key** vs (b) bcrypt/argon2.
- Rate limit: **(a) Redis Lua token bucket** vs (b) Bucket4j-Redis vs (c) in-memory.
- Budget: **(a) pre-flight estimate + post-call reconcile in Redis counters** vs (b) ledger-only checks.

## Decision outcome

- **Keys:** format `lgw_<keyId>_<secret>` (32+ random bytes). Store `key_id` (lookup) + `SHA-256(secret)` (constant-time compare). Fast hashing is appropriate because keys are random, not human passwords. Tenant + key rows live in **Postgres** (ADR-0008), cached in-process with short TTL (Caffeine) to keep Postgres off the hot path. Accept `Authorization: Bearer` (required) and `api-key` header (optional, Azure-style).
- **Rate limit:** atomic **Lua token bucket** per tenant (`rl:{tenant}`), capacity + refill rate per tenant; script returns allowed/remaining/retry-after. Optional second bucket on tokens-per-minute.
- **Budgets:** Redis counters `budget:{tenant}:{window}` for tokens and micro-dollars. Pre-flight: reject if counter + estimated prompt tokens (+ `max_tokens`) exceeds limit. Post-call: increment by actual usage. Ledger (ADR-0008) remains source of truth; counters can be rebuilt from it.
- **Contract:** 401 `invalid_api_key`; 403 `tenant_disabled`; 429 `rate_limit_exceeded` | `token_budget_exceeded` | `cost_budget_exceeded`, with `Retry-After` and `X-RateLimit-Limit/Remaining/Reset` headers; OpenAI-style error body (API-004).
- Streaming: pre-flight check only; actual usage reconciled at stream end (may overshoot by one response).

## Consequences

- ➕ Atomic, horizontally safe limits (NFR-009); no per-node affinity.
- ➕ Clear, parseable 429 reasons for clients.
- ➖ Budget enforcement is approximate: concurrent in-flight requests and streaming can exceed the budget slightly. Hard caps would need reservation/escrow — deferred.
- ➖ Revoking a key takes up to the in-process cache TTL to take effect.
- ➖ Writing our own Lua instead of Bucket4j: more code to test, but no extra dependency and easy to explain in interviews.
- ➖ Redis outage policy (fail-open vs fail-closed) must be chosen — **proposed: fail-closed (503)** for auth-critical paths; reviewer to confirm.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
