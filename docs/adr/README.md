# Architecture Decision Records — `jvm-llm-gateway`

Decisions on *how* to build what [SDR-0001 v1.0 (APPROVED)](../sdr/0001-jvm-llm-gateway-sdr.md) requires. Template and lifecycle: [ADR-0001](./0001-record-architecture-decisions.md). Review: [REVIEW.md](./REVIEW.md).

| ADR | Title | Status |
|---|---|---|
| [0001](./0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](./0002-build-java21-spring-boot-gradle-multimodule.md) | Build: Java 21, Spring Boot 3.x, Gradle multi-module | Accepted |
| [0003](./0003-http-stack-spring-webflux.md) | HTTP stack: Spring WebFlux end to end | Accepted |
| [0004](./0004-provider-abstraction-spring-ai.md) | Provider abstraction via Spring AI (OpenAI, Anthropic, Ollama) | Accepted; OpenAI amended by [0012](./0012-openai-adapter-direct-webclient.md), Anthropic by [0013](./0013-anthropic-adapter-direct-webclient.md). Ollama is [0015](./0015-ollama-webclient-and-public-embeddings.md) (Proposed) |
| [0005](./0005-routing-yaml-aliases-resilience4j.md) | Routing policy in YAML with aliases; Resilience4j retry / circuit breaker / fallback | Accepted |
| [0006](./0006-caching-redis-exact-and-semantic.md) | Caching: Redis Stack exact + semantic cache with TTL and bounded size | Accepted |
| [0007](./0007-tenancy-api-keys-rate-limits-budgets.md) | Tenancy and rate limiting: hashed API keys, Postgres tenants, Redis token bucket, budgets, 429 contract | Accepted |
| [0008](./0008-persistence-postgres-flyway-usage-ledger.md) | Persistence: Postgres + Flyway for tenants and usage ledger, 30-day retention | Accepted |
| [0009](./0009-pii-redaction-pipeline.md) | PII redaction: redact-and-forward pipeline with pluggable detectors | Accepted |
| [0010](./0010-observability-and-operations.md) | Observability and ops: Micrometer, OpenTelemetry, Prometheus/Grafana, actuator on management port | Accepted |
| [0011](./0011-testing-ci-and-licensing.md) | Testing, CI and licensing: JUnit 5, WireMock, Testcontainers, GitHub Actions, JaCoCo, Apache-2.0 | Accepted |
| [0012](./0012-openai-adapter-direct-webclient.md) | OpenAI adapter: direct WebClient behind `ProviderClient` | Accepted |
| [0013](./0013-anthropic-adapter-direct-webclient.md) | Anthropic adapter: direct WebClient and OpenAI wire translation | Accepted |
| [0014](./0014-guardrail-limits-and-week5-metrics.md) | Guardrail limits, placeholder identity, and the week-5 metric extensions | Accepted |
| [0015](./0015-ollama-webclient-and-public-embeddings.md) | Ollama WebClient adapter and the public embeddings route | Proposed |

**Process:** Proposed → reviewed by Gautam Gupta → Accepted / Rejected. No implementation before acceptance. Superseding decisions get a new number.
