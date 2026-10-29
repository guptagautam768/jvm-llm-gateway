# ADR-0006: Caching: Redis Stack exact + semantic cache with TTL and bounded size

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

FR-030–035 require an exact-match cache, a semantic (vector) cache with a similarity threshold, a bypass header, metrics, and — per RD-003 ("both") — **both TTL and a bounded size with LRU-style eviction**. NFR-004 requires redaction before cache write.

## Decision drivers

- G-005, FR-030, FR-031, FR-032, FR-033, FR-034, FR-035, RD-003, NFR-004, DATA-003, AC-006/007/008.

## Considered options

1. **Redis Stack (Redis + RediSearch vector index)** for both caches.
2. Redis for exact + pgvector (Postgres) for semantic.
3. In-process Caffeine (exact) + external vector DB.

## Decision outcome

**Chosen: option 1 — one Redis Stack instance** (also used for rate limiting, ADR-0007).

- **Exact key:** `cache:x:{tenant}:{sha256(canonical JSON of route, resolved model, redacted messages, temperature, max_tokens, ...)}`. Tenant-scoped — no cross-tenant hits.
- **Semantic:** embed the redacted final user prompt (configurable embedding route, default Ollama/OpenAI); RediSearch HNSW index with a `tenant` + `model` TAG filter; hit if cosine similarity ≥ `gateway.cache.semantic.threshold` (configurable; default chosen empirically during implementation — no number committed here). Only for requests with `temperature` ≤ configured max (deterministic-ish).
- **TTL:** every entry written with `EXPIRE` (separate configurable TTLs for exact and semantic).
- **Size bound (RD-003 interpretation):** Redis `maxmemory` + `allkeys-lru` for the cache instance, **plus** an application cap on semantic entries (`max-entries`; oldest trimmed via a sorted-set index) because vector-index memory isn't well governed by `maxmemory` alone.
- **Persistence:** cache payloads are ephemeral — Redis RDB/AOF **disabled** for the cache in the reference compose (resolves the "disk snapshot" sub-question in RD-003; changes if ADR-0007 shares the instance and needs durability — see consequences).
- **Streaming:** no cache read/write for `stream=true` in MVP (FR-033); a later ADR may enable "assemble-then-store".
- **Bypass:** `X-Cache-Bypass: true` skips lookup *and* write. Hit/miss/type recorded in metrics and ledger (FR-034).

## Consequences

- ➕ One dependency for cache + vectors + rate limits; simple local compose.
- ➕ Both TTL and size bound satisfy RD-003 as interpreted.
- ➖ Semantic caching can return a *wrong* answer for a similar-but-different prompt — the threshold is a correctness/hit-rate trade-off and must be tunable per route; default off for sensitive routes.
- ➖ Each semantic lookup costs an embedding call (latency + cost on miss).
- ➖ `allkeys-lru` on a shared instance could evict rate-limit keys; **recommend separate logical/physical Redis for cache vs. limits** (reviewer decision).
- ➖ Redis Stack licensing (RSAL/SSPL for modules, AGPL option in Redis 8) differs from Apache-2.0; fine for a dependency, but note it in README.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
