# ADR Review Checklist — `jvm-llm-gateway`

**Reviewer:** Gautam Gupta  
**Set under review:** ADR-0001 … ADR-0011 (all *Proposed*, 29 Oct 2026 IST)  
**Requirements baseline:** SDR-0001 v1.0 (APPROVED)

## A. Cross-cutting

- [ ] Every Must FR/NFR in the SDR is covered by at least one ADR.
- [ ] No ADR contradicts a resolved decision (RD-001…RD-008).
- [ ] Trade-offs are honest; no invented benchmark numbers.
- [ ] Module layout (ADR-0002) is the right size for an MVP.

## B. Points needing an explicit call

- [ ] RD-003 interpretation (TTL **and** size bound / LRU) is correct — ADR-0006.
- [ ] Separate Redis for cache vs. rate limits (to avoid LRU evicting limit keys) — ADR-0006/0007.
- [ ] Redis-outage policy: fail-closed (503) — ADR-0007.
- [ ] Budgets are approximate (can overshoot under concurrency/streaming) — ADR-0007.
- [ ] R2DBC runtime + JDBC for Flyway — ADR-0003/0008.
- [ ] Daily partitioning vs. simple table + DELETE for 30-day retention — ADR-0008.
- [ ] Gradle (not Maven) — ADR-0002.

## C. Per-ADR decisions

### ADR-0001 — Record architecture decisions
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0002 — Build: Java 21, Spring Boot 3.x, Gradle multi-module
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0003 — HTTP stack: Spring WebFlux end to end
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0004 — Provider abstraction via Spring AI (OpenAI, Anthropic, Ollama)
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0005 — Routing policy in YAML with aliases; Resilience4j retry / circuit breaker / fallback
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0006 — Caching: Redis Stack exact + semantic cache with TTL and bounded size
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0007 — Tenancy and rate limiting: hashed API keys, Postgres tenants, Redis token bucket, budgets, 429 contract
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0008 — Persistence: Postgres + Flyway for tenants and usage ledger, 30-day retention
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0009 — PII redaction: redact-and-forward pipeline with pluggable detectors
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0010 — Observability and ops: Micrometer, OpenTelemetry, Prometheus/Grafana, actuator on management port
- [ ] Approve
- [ ] Changes requested: ________

### ADR-0011 — Testing, CI and licensing: JUnit 5, WireMock, Testcontainers, GitHub Actions, JaCoCo, Apache-2.0
- [ ] Approve
- [ ] Changes requested: ________

## D. Overall

- [ ] **Accept all** (statuses → Accepted; start milestone 1)
- [ ] **Accept with changes** (list above, re-review changed ADRs)
- [ ] **Reject set** — reason: ________

**Signature / date:** _______________________ (IST)


---
**Outcome (29 Oct 2026 IST):** All 11 ADRs APPROVED as-is by Gautam Gupta; status set to Accepted. Development authorized.
