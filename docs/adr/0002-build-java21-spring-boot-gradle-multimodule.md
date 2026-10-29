# ADR-0002: Build: Java 21, Spring Boot 3.x, Gradle multi-module

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

NFR-010 fixes Java 21 and Spring Boot 3.x. The open choice is the build tool and module layout. The gateway has clearly separable concerns — API layer, routing/core, provider adapters, cache, tenancy, persistence — and providers must be swappable (G-002) and testable in isolation (NFR-007).

## Decision drivers

- NFR-010 (Java 21, Boot 3.x), NFR-007 (testability), G-002 (pluggable providers), G-011 (readable structure), AC-014 (CI).

## Considered options

1. **Gradle (Kotlin DSL) multi-module** with version catalog (`gradle/libs.versions.toml`).
2. Maven multi-module (parent POM + BOM).
3. Single module with packages only.

## Decision outcome

**Chosen: option 1 — Gradle Kotlin DSL, multi-module.**

```
jvm-llm-gateway/
├─ gateway-app/          # Spring Boot app, wiring, config binding, WebFlux handlers
├─ gateway-api/          # OpenAI-compatible DTOs, error model (API-*)
├─ gateway-core/         # routing, aliases, resilience, pipeline (no Spring Web deps)
├─ gateway-providers/    # openai, anthropic, ollama adapters (Spring AI)
├─ gateway-cache/        # exact + semantic cache (Redis)
├─ gateway-tenancy/      # API keys, rate limits, budgets
├─ gateway-persistence/  # Postgres/Flyway, usage ledger
├─ gateway-guardrails/   # PII redaction, size limits
└─ build-logic/          # convention plugins (java, test, jacoco)
```

Java 21 via Gradle toolchains; Spring Boot BOM via the Spring dependency-management plugin; module boundaries enforced by dependencies (core must not depend on app/web). Providers may later split into one module per provider if dependency weight warrants it.

## Consequences

- ➕ Fast incremental builds and build cache; convention plugins keep module builds DRY.
- ➕ Enforced boundaries make "core has no web/provider dependencies" checkable.
- ➖ Gradle is more flexible but less uniform than Maven; many enterprise JVM shops (and some reviewers) are Maven-first. Honest trade-off: Maven would be equally valid — this is partly a preference/portfolio-signal choice.
- ➖ Eight modules is more ceremony than an MVP strictly needs; could start with fewer and split later (reviewer call).

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
