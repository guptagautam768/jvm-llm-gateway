# SDR Review Checklist — `jvm-llm-gateway`

Use this checklist when reviewing [0001-jvm-llm-gateway-sdr.md](./0001-jvm-llm-gateway-sdr.md).  
**Gate:** do not start ADRs or implementation until Status is **APPROVED** (or Approved with changes landed in the SDR).

**Reviewer:** Gautam Gupta  
**Document under review:** SDR-0001 v0.1 (DRAFT) → approved as v1.0  
**Date opened:** 24 Oct 2026 IST  
**Date closed:** 29 Oct 2026 IST  

---

## A. Process

- [x] Confirmed this is a **requirements** doc (SDR), not an architecture decision set (ADR).
- [x] Scope does **not** duplicate `resume-jd-matcher` (no RAG product / MCP agent app in MVP).
- [x] Non-goals still correct: **no admin UI**, **no billing**, **no k8s operator**.
- [x] Willing to answer Open Questions before approval (section C).

## B. Content quality

- [x] Problem statement matches the platform LLM-access story.
- [x] Goals / non-goals are complete enough for MVP.
- [x] Personas and use cases cover developers, platform, SRE, security, portfolio reader.
- [x] Functional IDs (FR-*) cover: OpenAI-compatible chat+embeddings, multi-provider, YAML routing/aliases, Resilience4j, Redis exact+semantic cache, API-key tenancy, rate limits + token/$ budgets, PII redaction, SSE, usage ledger, health/actuator.
- [x] NFR latency numbers accepted as **proposals** (or edited with preferred targets).
- [x] API contracts (API-*) are sufficient at high level; OpenAPI can wait for post-ADR.
- [x] Configuration surface lists *what*, not *how*.
- [x] Data retained section is accurate; unknowns left open.
- [x] Acceptance criteria (AC-*) are testable for an MVP tag.
- [x] Tone is portfolio-quality (clear, professional, no fluff).

## C. Open questions — record decisions

| ID | Decision (fill in) | Notes |
|---|---|---|
| OQ-001 Second provider | **Anthropic** | No Bedrock in MVP; Bedrock = future (F-002) |
| OQ-002 License | **Apache-2.0** |  |
| OQ-003 Cache retention | **Both: TTL + bounded size/LRU-style eviction** | “Both” interpreted as TTL AND size bound for exact + semantic caches; confirm in ADR-0006 |
| OQ-004 Actuator exposure | **Separate management port only** | Not on public API port |
| OQ-005 Repo name | **`jvm-llm-gateway`** | Repo created 24 Oct 2026 |
| OQ-006 PII default | **Redact-and-forward** | Reject-on-detect remains configurable |
| OQ-007 Ledger retention | **30 days** |  |
| OQ-008 SSE stack flexibility | **WebFlux required** |  |

## D. Edits requested

List concrete SDR text changes (section + ask), or write “None”.

None to requirements beyond resolving open questions (applied in v1.0: G-002, UC-007, AC-010, AC-013, FR-011, FR-035 new, FR-052, FR-062 new, FR-072, NFR-004, CFG-009, DATA-002, DATA-003, F-002, §11).

## E. Decision

- [x] **Approved** — SDR approved as-is 25 Oct 2026; open questions resolved 29 Oct 2026; status → APPROVED v1.0; proceed to ADRs.
- [ ] Approve with changes
- [ ] Reject

**Signature / date:** Gautam Gupta — 29 Oct 2026 (IST)

---

## After approval

1. Update SDR status/version and fill the Approval table in the SDR.
2. Draft ADRs (routing, cache, persistence, HTTP stack, tenancy schema, etc.).
3. Review/approve ADRs.
4. Begin milestone 1 implementation (repo already created 24 Oct 2026).

Status: steps 1–2 done 29 Oct 2026 — ADRs drafted as **Proposed** in [`../adr/`](../adr/README.md).
