# SDR-0001: JVM LLM Gateway — Software Design Requirements

| Field | Value |
|---|---|
| **Document ID** | SDR-0001 |
| **Title** | JVM LLM Gateway — Software Design Requirements |
| **Status** | APPROVED |
| **Version** | 1.0 |
| **Author** | Grok Bot (for Gautam Gupta) |
| **Date** | 24 Oct 2026 IST |
| **Audience** | Gautam Gupta (owner / reviewer), future collaborators, portfolio readers |
| **Next gate** | ADR set (Proposed) → ADR review → implementation |
| **Related repos** | Must not duplicate `resume-jd-matcher` (RAG + MCP application). This project is platform infrastructure for LLM access. |
| **Repo** | `jvm-llm-gateway` — https://github.com/guptagautam768/jvm-llm-gateway (created 24 Oct 2026; RD-005) |
| **License** | Apache-2.0 (RD-002) |

---

## 1. Purpose and problem statement

### 1.1 Purpose

Define the **requirements** for an open-source, JVM-based **LLM Gateway**: a Spring Boot service that sits between many application teams and one or more LLM providers. This document is intentionally a requirements specification. Architecture choices (module layout, Redis vs alternatives, concrete schema; WebFlux is fixed by RD-008) belong in subsequent ADRs after this SDR is approved.

### 1.2 Problem statement

Organizations that adopt GenAI quickly accumulate many apps that each call providers (OpenAI, Anthropic, Bedrock, local models) with their own keys, retry logic, logging, and cost tracking. The result is:

- Inconsistent reliability (no shared retry, circuit breaker, or fallback).
- Opaque spend (no per-team token or dollar budgets).
- Repeated PII leakage risk (prompts leave the org without redaction).
- Duplicate client glue across teams.
- Hard-to-swap models (apps hard-code provider APIs).

`jvm-llm-gateway` must give teams a single, OpenAI-compatible control plane for chat and embeddings so existing SDKs keep working, while the platform owns routing, resilience, caching, tenancy, guardrails, and usage visibility.

### 1.3 Why this project (portfolio context)

The gateway demonstrates Staff-level platform judgement: multi-tenancy, resilience, cost and latency trade-offs, observability, and GenAI infrastructure — complementary to, not overlapping with, the existing RAG application `resume-jd-matcher`.

---

## 2. Goals and non-goals

### 2.1 Goals (MVP)

| ID | Goal |
|---|---|
| G-001 | Provide OpenAI-compatible **chat completions** and **embeddings** APIs so clients can use standard OpenAI SDKs with a base-URL change. |
| G-002 | Support **multiple providers**: OpenAI (required), a second cloud provider (**Anthropic**; Amazon Bedrock deferred to a future phase — RD-001), and **Ollama** for local/CI without paid keys. |
| G-003 | Express **routing and model aliases** in configuration (YAML), including primary → fallback chains. |
| G-004 | Apply **Resilience4j** retry, circuit breaker, and fallback across provider calls. |
| G-005 | Offer **Redis exact-match cache** and **semantic (vector) cache** for eligible chat responses. |
| G-006 | Enforce **API-key → tenant** identity, **rate limits**, and **token / $ budgets** with clear 429 responses. |
| G-007 | Apply **PII redaction** (and request size caps) before provider calls. |
| G-008 | Support **SSE streaming** for chat completions. |
| G-009 | Record a **usage ledger** (tokens, estimated cost, tenant, model, cache hit/miss) and expose **health / actuator** for ops. |
| G-010 | Ship a **local-dev path** (`docker compose` + Ollama) that works without paid provider keys. |
| G-011 | Be **recruiter- and interview-ready**: clear docs, tests, CI, and demonstrable trade-offs. |

### 2.2 Non-goals (MVP)

| ID | Non-goal | Rationale |
|---|---|---|
| NG-001 | **Admin UI / console** | Config files + actuator (or equivalent ops endpoints) are enough for MVP. UI is a later phase. |
| NG-002 | **Billing / invoicing / payment** | Usage ledger and budgets only; no payment processor or invoice generation. |
| NG-003 | **Kubernetes operator / CRDs** | Docker Compose for local; optional later deploy recipe (e.g. ECS) is out of MVP product scope. |
| NG-004 | **RAG, document ingest, or MCP agent product** | Those belong in apps (e.g. `resume-jd-matcher`), not in this gateway. |
| NG-005 | **Full OpenAI API surface** | Only chat completions and embeddings in MVP (not Assistants, Images, Audio, Fine-tuning, Files, etc.). |
| NG-006 | **Multi-region active-active HA design** | Single-region / single-instance or simple HA story is enough; mark true multi-region as future. |
| NG-007 | **Guaranteed exact cost parity with provider invoices** | Estimated cost from configured price tables is sufficient for MVP visibility. |

---

## 3. Personas and use cases

### 3.1 Personas

| ID | Persona | Needs |
|---|---|---|
| P-001 | **App developer** | Drop-in OpenAI SDK compatibility; clear errors; streaming; local Ollama for offline work. |
| P-002 | **Platform / Staff engineer** | Central routing, resilience, budgets, PII controls, metrics, and a usage ledger across teams. |
| P-003 | **SRE / on-call** | Health endpoints, metrics, traces, predictable failure modes, circuit breakers. |
| P-004 | **Security / compliance reviewer** | API-key tenancy, redaction before egress, no unnecessary prompt retention, auditable usage. |
| P-005 | **Portfolio reviewer / hiring manager** | Readable requirements → ADRs → working code trail; Staff-level trade-offs visible in docs. |

### 3.2 Use cases

| ID | Use case | Actor | Summary | Priority |
|---|---|---|---|---|
| UC-001 | Chat via OpenAI SDK | P-001 | Point SDK at gateway; call chat completions with alias or model name; receive OpenAI-shaped response. | Must |
| UC-002 | Embeddings via OpenAI SDK | P-001 | Same as UC-001 for `/v1/embeddings`. | Must |
| UC-003 | Local demo without paid keys | P-001, P-005 | `docker compose up`; route to Ollama; successful chat + embeddings. | Must |
| UC-004 | Provider outage fallback | P-002, P-003 | Primary provider fails/times out; Resilience4j path falls back per route config; client still succeeds when fallback healthy. | Must |
| UC-005 | Cache hit reduces cost/latency | P-002 | Repeated / similar prompt (per cache rules) returns cached completion; usage marks cache hit; provider not called. | Must |
| UC-006 | Tenant over budget | P-002 | Tenant exceeds rate or token/$ budget; gateway returns **429** with machine-readable reason. | Must |
| UC-007 | PII redacted before provider | P-004 | Prompt containing detectable PII patterns is redacted and the request forwarded (default policy, RD-006) before provider call. | Must |
| UC-008 | Stream chat tokens | P-001 | Client requests `stream=true`; receives SSE events compatible with OpenAI streaming shape. | Must |
| UC-009 | Ops health check | P-003 | Liveness/readiness (actuator) reflects dependency health at a stated level of detail. | Must |
| UC-010 | Usage report query | P-002 | Operator can inspect per-tenant token/cost aggregates from the ledger (mechanism may be SQL/metrics; UI out of scope). | Should |

---

## 4. Functional requirements

Requirements use MoSCoW priority: **Must** / **Should** / **Could**.

### 4.1 API compatibility

| ID | Requirement | Priority |
|---|---|---|
| FR-001 | The gateway SHALL expose `POST /v1/chat/completions` with request/response fields sufficient for common OpenAI SDK chat usage (model, messages, temperature, max_tokens / max_completion_tokens as supported, stream, user). Unsupported fields SHALL be ignored or rejected consistently (document which). | Must |
| FR-002 | The gateway SHALL expose `POST /v1/embeddings` with OpenAI-compatible input (model, input string or array) and embedding vector response shape. | Must |
| FR-003 | Clients SHALL authenticate with an API key via `Authorization: Bearer <key>` (and/or `api-key` header — decide exact accepted headers in ADR; at least Bearer MUST work). | Must |
| FR-004 | Model **aliases** (e.g. `fast`, `smart`) SHALL resolve via configuration to concrete provider models. | Must |

### 4.2 Multi-provider routing

| ID | Requirement | Priority |
|---|---|---|
| FR-010 | The gateway MUST support **OpenAI** as a provider for chat and embeddings. | Must |
| FR-011 | The gateway MUST support **Anthropic** as the second cloud provider in MVP (chat). Amazon Bedrock is out of MVP scope (future, F-002) — RD-001. | Must |
| FR-012 | The gateway MUST support **Ollama** for local development and CI (chat at minimum; embeddings if the chosen local model supports them). | Must |
| FR-013 | Routing policy SHALL be expressible in **YAML** (or equivalent declarative config): alias → primary model/provider, ordered fallbacks, and per-route resilience hints as needed. | Must |
| FR-014 | On primary failure (timeout, 5xx, circuit open), the gateway SHALL attempt configured fallbacks before failing the client request. | Must |

### 4.3 Resilience

| ID | Requirement | Priority |
|---|---|---|
| FR-020 | Provider calls SHALL use **Resilience4j** (or Spring Cloud Circuit Breaker backed by Resilience4j) for **retry** and **circuit breaker**. | Must |
| FR-021 | Retry SHALL be bounded (max attempts, backoff policy configurable). Non-retryable client errors (4xx except 429 where appropriate) SHALL not be blindly retried. | Must |
| FR-022 | When the circuit is open, the gateway SHALL fail fast to fallback or return a structured error without hanging the client. | Must |

### 4.4 Caching

| ID | Requirement | Priority |
|---|---|---|
| FR-030 | An **exact-match cache** (hash of normalized cache key) SHALL be checked before provider calls for eligible non-streaming chat requests. | Must |
| FR-031 | A **semantic cache** (embedding + Redis vector similarity) SHALL be checked when exact miss, subject to a configurable similarity threshold, TTL, and bounded size/eviction (RD-003). | Must |
| FR-035 | Both exact and semantic caches SHALL enforce a configurable **TTL** AND a configurable **size bound** (max entries and/or memory) with LRU-style eviction when the bound is reached (RD-003). | Must |
| FR-032 | Clients SHALL be able to bypass cache via a documented request header (e.g. `X-Cache-Bypass: true`). | Must |
| FR-033 | Streaming requests MAY skip semantic/exact response caching in MVP unless an ADR explicitly enables a safe strategy. | Should |
| FR-034 | Cache hit/miss SHALL be visible in metrics and in the usage ledger. | Must |

### 4.5 Tenancy, rate limits, budgets

| ID | Requirement | Priority |
|---|---|---|
| FR-040 | Each API key SHALL map to a **tenant** (identity used for limits, budgets, and usage attribution). | Must |
| FR-041 | The gateway SHALL enforce **per-tenant rate limits** (token-bucket or equivalent) backed by Redis. | Must |
| FR-042 | The gateway SHALL enforce **per-tenant token and/or estimated-$ budgets** over a configurable window (e.g. monthly). | Must |
| FR-043 | Exceeding rate limit or budget SHALL return **HTTP 429** with a stable error code/reason the client can parse. | Must |
| FR-044 | Invalid or missing API keys SHALL return **HTTP 401** (or 403 if key valid but disabled — document distinction). | Must |

### 4.6 Guardrails

| ID | Requirement | Priority |
|---|---|---|
| FR-050 | Before provider egress, the gateway SHALL apply **PII redaction** using configurable patterns (regex baseline; Presidio-style integration is Could). | Must |
| FR-051 | The gateway SHALL enforce a **maximum request size** (payload / message length) and reject oversized requests with **413** or **400** and a clear code. | Must |
| FR-052 | Redaction behavior (mask vs reject) SHALL be configurable per deployment; MVP default is **redact-and-forward** (RD-006); reject-on-detect is an opt-in mode. | Should |

### 4.7 Streaming

| ID | Requirement | Priority |
|---|---|---|
| FR-062 | The HTTP stack SHALL be **Spring WebFlux** (reactive, end to end) from MVP; Spring MVC + SSE is not an accepted alternative (RD-008). | Must |
| FR-060 | When `stream=true`, the gateway SHALL stream chat completion chunks to the client as **SSE**, preserving OpenAI-compatible event shapes where practical. | Must |
| FR-061 | Streamed responses SHALL still honor auth, rate limits, budgets (pre-flight or metered), and PII redaction on the prompt side. | Must |

### 4.8 Usage ledger and ops

| ID | Requirement | Priority |
|---|---|---|
| FR-070 | The gateway SHALL persist a **usage ledger** entry per billable or metered request (tenant, route/model, provider, prompt/completion tokens as available, estimated cost, cache hit flag, latency, status). | Must |
| FR-071 | Estimated cost SHALL use a **configurable price table** (model → $/1K tokens); missing prices SHALL record tokens with cost null/zero and a metric/warning. | Must |
| FR-072 | Spring Boot **Actuator** health (and info as appropriate) SHALL be exposed **only on a separate management port**, never on the public API port (RD-004). | Must |
| FR-073 | Metrics SHALL include at least: request count, errors, tokens, estimated cost, cache hit ratio, per-provider latency timers (Micrometer). | Must |
| FR-074 | Distributed traces SHALL be exportable via OpenTelemetry for a typical local/dev collector setup. | Should |

---

## 5. Non-functional requirements

Latency numbers below are **proposed targets for review**, not measured production SLOs. They will be validated with load tests during implementation.

| ID | Area | Requirement | Priority |
|---|---|---|---|
| NFR-001 | Latency | **Proposed:** p95 gateway overhead (excluding provider time) ≤ **50 ms** for cache miss path auth+route+meter on a local/dev-class machine; p95 cache-hit total response ≤ **30 ms** for exact-match hits. Revisit after Gatling benchmarks. | Should |
| NFR-002 | Availability | MVP aims for single-instance correctness and clean failure modes, not a formal multi-AZ SLO. Document restart/drain behavior. Formal availability % is **Open** until deploy topology is chosen. | Could |
| NFR-003 | Security | Secrets (provider keys, tenant keys) NEVER in git; injectable via env/secret store. Tenant keys stored hashed at rest if persisted. TLS termination may be external in MVP. | Must |
| NFR-004 | Privacy | Prompts/completions are NOT stored in the usage ledger by default. Cache content is bounded by TTL + size/eviction (RD-003). PII redaction runs before provider and before cache write. | Must |
| NFR-005 | Cost visibility | Per-tenant estimated cost and token totals queryable from ledger and/or metrics within the retention window. | Must |
| NFR-006 | Observability | Micrometer metrics + OTel traces; sample Grafana dashboard JSON in repo. Structured logs with correlation/trace ids; no secrets or raw PII in logs. | Must |
| NFR-007 | Testability | Unit tests for router, limits, redaction; WireMock (or equivalent) for provider failures; Testcontainers for Redis/Postgres; at least one contract test with an OpenAI-compatible client; CI on PR. | Must |
| NFR-008 | Local-dev | Full happy path without paid keys via Docker Compose + Ollama. Paid providers optional via env. | Must |
| NFR-009 | Scalability | MVP need not prove horizontal scale; design SHOULD avoid hard single-node affinity beyond Redis/Postgres. Load-test results documented qualitatively for portfolio. | Should |
| NFR-010 | Compatibility | Java 21; Spring Boot 3.x; documented minimum Docker Compose for dependencies. | Must |

---

## 6. External API contracts (high level)

Detailed OpenAPI may follow after ADR approval. Shapes below are **requirements-level**.

### 6.1 Common

| ID | Contract |
|---|---|
| API-001 | Base path prefix: `/v1`. |
| API-002 | Auth: `Authorization: Bearer <tenant-api-key>` required on chat and embeddings. |
| API-003 | `Content-Type: application/json` for non-stream requests; SSE for streamed chat. |
| API-004 | Error body (non-stream) SHOULD follow a stable JSON shape, e.g. `{ "error": { "code": "...", "message": "...", "type": "..." } }` compatible enough for OpenAI clients where practical. |

### 6.2 Chat completions

| ID | Contract |
|---|---|
| API-010 | `POST /v1/chat/completions` |
| API-011 | Request (minimum fields): `model` (string; alias or concrete), `messages` (array of `{role, content}`), optional `temperature`, optional `max_tokens` / `max_completion_tokens`, optional `stream` (boolean), optional `user`. |
| API-012 | Non-stream response (minimum fields): `id`, `object` (`chat.completion`), `created`, `model`, `choices[]` with `index`, `message`, `finish_reason`, and `usage` with token counts when available. |
| API-013 | Stream response: `text/event-stream` with `data:` lines as chat.completion.chunk objects; terminal `[DONE]` per OpenAI convention where feasible. |

### 6.3 Embeddings

| ID | Contract |
|---|---|
| API-020 | `POST /v1/embeddings` |
| API-021 | Request: `model`, `input` (string or array of strings). |
| API-022 | Response: `object` (`list`), `data[]` with `embedding` (number array), `index`, `usage` when available. |

### 6.4 Error codes (minimum set)

| ID | HTTP | When | Notes |
|---|---|---|---|
| API-030 | **401** | Missing/invalid API key | |
| API-031 | **403** | Key valid but tenant disabled / not allowed | If distinct from 401 |
| API-032 | **400** | Malformed JSON / invalid params | |
| API-033 | **413** | Request too large | Or 400 with specific code — pick one in ADR |
| API-034 | **429** | Rate limit or budget exceeded | Body MUST include reason enum/string: e.g. `rate_limit`, `token_budget`, `cost_budget` |
| API-035 | **502 / 504** | Upstream provider failure / timeout after fallbacks exhausted | Map precisely in ADR |
| API-036 | **503** | Gateway not ready / dependency down | Align with actuator readiness |

---

## 7. Configuration surface

This section lists **what must be configurable**. **How** it is loaded (files, env overlays, DB) is deferred to ADRs.

| ID | Configurable concern |
|---|---|
| CFG-001 | Provider enablement and credentials references (env keys, not plaintext in repo). |
| CFG-002 | Model aliases and primary/fallback routes. |
| CFG-003 | Resilience4j retry/circuit-breaker parameters (global and/or per route). |
| CFG-004 | Cache: enable/disable, TTLs, semantic similarity threshold, embedding model for cache keys, bypass behavior. |
| CFG-005 | Tenant API keys (bootstrap), rate-limit capacities/refill, token and $ budget windows and limits. |
| CFG-006 | PII patterns / redaction mode; max request size. |
| CFG-007 | Price table for estimated cost. |
| CFG-008 | Observability export endpoints (OTLP, Prometheus scrape). |
| CFG-009 | Management (actuator) port and exposed endpoint set; management port distinct from API port (RD-004). |

---

## 8. Data retained

| ID | Data | Stored? | Notes |
|---|---|---|---|
| DATA-001 | Tenant records (id, status, limits, key hashes) | Yes | Persistence store TBD in ADR (likely Postgres). |
| DATA-002 | Usage ledger rows | Yes | No raw prompt/completion by default. Retained **30 days**, then purged (RD-007). |
| DATA-003 | Exact + semantic cache entries (key/vector + response payload) | Yes (ephemeral cache) | Configurable TTL **and** bounded size with LRU-style eviction (RD-003). |
| DATA-004 | Provider secrets | Config/runtime only | Not in application DB as plaintext. |
| DATA-005 | Full prompt/response audit log | **No (MVP default)** | Future phase if required. |

---

## 9. Acceptance criteria for MVP

Testable checklist. All Must items SHOULD pass before v0.1.0 tag.

| ID | Criterion | How verified |
|---|---|---|
| AC-001 | OpenAI Python or Java SDK can complete a chat call against the gateway with only base URL + API key changed. | Contract / manual test documented in README |
| AC-002 | Embeddings call succeeds with OpenAI-compatible response shape. | Automated test |
| AC-003 | `docker compose up` + documented curl/SDK example works with **Ollama only** (no paid keys). | Manual quickstart + CI smoke where feasible |
| AC-004 | YAML route with fallback: WireMock (or equiv.) primary 500/timeout → fallback succeeds. | Automated test |
| AC-005 | Resilience4j circuit opens after configured failures and recovers per config. | Automated test |
| AC-006 | Exact cache: identical eligible request → second call is cache hit (provider not called). | Automated test |
| AC-007 | Semantic cache: near-duplicate prompt above threshold → hit; below → miss. | Automated test |
| AC-008 | Cache-bypass header forces provider call. | Automated test |
| AC-009 | Unknown API key → 401; over rate/budget → 429 with reason. | Automated test |
| AC-010 | Prompt with synthetic PII is redacted (default redact-and-forward, RD-006) before mocked provider sees it. | Automated test |
| AC-011 | `stream=true` yields SSE chunks parseable as OpenAI stream. | Automated or scripted test |
| AC-012 | Usage ledger row written with tenant, tokens, estimated cost (or null if no price), cache flag. | Automated test |
| AC-013 | Actuator health endpoint responds on the management port (not the API port, RD-004); metrics scrape shows request/token/cache series. | Automated / compose smoke |
| AC-014 | CI runs unit + container tests on PR; coverage reported. | GitHub Actions |
| AC-015 | README includes architecture diagram, quickstart, and link to this SDR; ADRs added only after SDR approval. | Doc review |

---

## 10. Out of scope and future phases

### 10.1 Out of scope (MVP) — restated

Admin UI, billing/payments, Kubernetes operator, full OpenAI API surface, RAG/MCP product features, guaranteed invoice-grade cost accuracy, multi-region active-active.

### 10.2 Future phases (indicative, not committed)

| Phase | Ideas |
|---|---|
| F-001 | Admin API/UI for tenants, keys, and budget adjustments. |
| F-002 | Additional providers (Amazon Bedrock, Azure OpenAI, Google, Groq, etc.). |
| F-003 | Response policy / toxicity filters beyond PII. |
| F-004 | Prompt/response optional audit store with encryption and retention jobs. |
| F-005 | Kubernetes Helm chart or operator. |
| F-006 | Chargeback export (CSV/Parquet) and invoice-grade cost reconciliation. |
| F-007 | Semantic cache for streaming or partial-chunk strategies. |

---

## 11. Resolved decisions (formerly Open questions)

All open questions were resolved by Gautam Gupta on 29 Oct 2026 IST. IDs are renamed OQ-00n → RD-00n; the affected sections above have been updated.

| ID | Question | Decision | Affects |
|---|---|---|---|
| **RD-001** (OQ-001) | Second cloud provider for MVP? | **Anthropic.** No Bedrock in MVP; Bedrock is a future provider (F-002). | G-002, FR-011 |
| **RD-002** (OQ-002) | License for the public repo? | **Apache-2.0.** | LICENSE, README badges |
| **RD-003** (OQ-003) | Cache retention policy? | **"Both"** — interpreted as: exact and semantic caches each use a configurable **TTL** *and* a **bounded size** (max entries and/or memory) with **LRU-style eviction**. *Interpretation recorded by the author; owner to confirm in ADR-0006 review.* Disk-snapshot persistence of cache payloads is left to ADR-0006. | FR-031, FR-035, NFR-004, DATA-003 |
| **RD-004** (OQ-004) | Actuator exposure? | **Separate management port only**; not exposed on the public API port. | FR-072, CFG-009 |
| **RD-005** (OQ-005) | Repo name? | **`jvm-llm-gateway`** confirmed; public repo created 24 Oct 2026. | Repo, badges, profile pin |
| **RD-006** (OQ-006) | Default PII policy? | **Redact-and-forward** by default; reject-on-detect configurable. | UC-007, FR-050, FR-052 |
| **RD-007** (OQ-007) | Usage ledger retention? | **30 days.** | DATA-002 |
| **RD-008** (OQ-008) | WebFlux required for SSE? | **Yes — WebFlux is required.** | FR-060, FR-062, ADR-0003 |

---

## 12. Glossary

| Term | Meaning |
|---|---|
| **SDR** | Software Design Requirements — *what* the system must do; this document. |
| **ADR** | Architecture Decision Record — *how* we decide to build it; created only after SDR approval. |
| **Alias** | Logical model name (e.g. `fast`) mapped by config to a provider-specific model. |
| **Tenant** | Isolating identity for keys, limits, budgets, and usage attribution. |
| **Exact cache** | Cache keyed by deterministic hash of the normalized request. |
| **Semantic cache** | Cache lookup by vector similarity of the prompt embedding against prior entries. |
| **Usage ledger** | Durable store of per-request metering fields (tokens, cost estimate, etc.). |
| **Fallback chain** | Ordered list of providers/models tried when the primary fails. |
| **SSE** | Server-Sent Events — streaming transport for `stream=true` chat. |
| **Resilience4j** | Library providing retry, circuit breaker, and related fault-tolerance patterns. |
| **Ollama** | Local LLM runtime used so demos/CI can run without paid API keys. |
| **OpenAI-compatible** | HTTP API shapes close enough that official/common OpenAI SDKs work with a custom base URL. |

---

## 13. Document history

| Version | Date (IST) | Author | Notes |
|---|---|---|---|
| 0.1 | 24 Oct 2026 | Grok Bot for Gautam Gupta | Initial DRAFT for human review. No ADRs or code until approval. |
| 1.0 | 29 Oct 2026 | Grok Bot for Gautam Gupta | APPROVED. SDR approved as-is 25 Oct; open questions resolved 29 Oct (§11 RD-001…RD-008) and affected FR/NFR/DATA/CFG text updated. |

---

## 14. Approval

| Role | Name | Decision | Date |
|---|---|---|---|
| Owner | Gautam Gupta | ☑ **Approved** | 29 Oct 2026 IST (SDR approved as-is 25 Oct 2026; open questions resolved 29 Oct 2026) |

**Process reminder:** Approve this SDR (with answers to Open Questions) → draft ADRs → approve ADRs → implement → review → live/demo release.
