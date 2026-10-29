# ADR-0011: Testing, CI and licensing: JUnit 5, WireMock, Testcontainers, GitHub Actions, JaCoCo, Apache-2.0

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

NFR-007 requires unit tests and provider-failure simulation; AC-001–014 need automated evidence; AC-014 requires CI with coverage; RD-002 fixes the license as **Apache-2.0**. CI must not need paid provider keys (NFR-008).

## Decision drivers

- NFR-007, NFR-008, AC-001…AC-014, RD-002, G-011.

## Considered options

- Provider fakes: **WireMock** vs MockWebServer vs real Ollama in CI.
- Infra: **Testcontainers** (Redis Stack, Postgres) vs embedded/in-memory substitutes.
- CI: **GitHub Actions** vs others.

## Decision outcome

- **Unit:** JUnit 5 + AssertJ + Reactor `StepVerifier`; BlockHound enabled in tests (ADR-0003).
- **Provider contract tests:** WireMock stubs per provider (OpenAI, Anthropic, Ollama) including 5xx, timeouts, 429, malformed and streamed responses → drives AC-004/005/011.
- **Integration:** `@SpringBootTest` + Testcontainers (`redis/redis-stack`, `postgres:16`) with `@ServiceConnection`; covers cache (AC-006–008), limits (AC-009), ledger (AC-012), retention job.
- **SDK compatibility (AC-001):** a test using the official OpenAI Java SDK pointed at the gateway (WireMock behind it).
- **Optional smoke:** nightly/manual workflow with a tiny Ollama model; not on every PR (slow, model download).
- **CI (GitHub Actions):** on PR/push — Gradle build with cache, tests, JaCoCo report (aggregated across modules) uploaded as artifact + PR summary; initial coverage *gate* set after first baseline rather than an invented number. Dependabot + CodeQL.
- **Licensing:** `LICENSE` (Apache-2.0), `NOTICE`, SPDX headers optional; README badge. Dependency license check (e.g. Gradle license report) in CI.

## Consequences

- ➕ Fully keyless, deterministic CI; failure modes testable on demand.
- ➕ Real Redis/Postgres semantics (Lua, vector index, partitions) instead of fakes.
- ➖ Testcontainers needs Docker in CI (fine on GH-hosted Ubuntu runners) and adds minutes to builds.
- ➖ WireMock stubs can drift from real provider APIs; mitigated by occasional manual runs against real keys (documented, not in CI).
- ➖ Apache-2.0 differs from MIT used in `resume-jd-matcher` — intentional (patent grant), but noted.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
