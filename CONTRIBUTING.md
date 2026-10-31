# Contributing

This repository is a portfolio project. Small, focused fixes are welcome. Behavior changes need an ADR when no Accepted ADR already covers the choice. See [docs/adr/0001-record-architecture-decisions.md](docs/adr/0001-record-architecture-decisions.md).

## Development

Java 21. Docker is required for `./gradlew build` because Testcontainers starts Postgres 16 and Redis Stack. WireMock tests do not need provider keys.

```bash
./gradlew test
./gradlew build
```

`./gradlew build` also writes the JaCoCo aggregate at `build/reports/jacoco/jacocoRootReport/`. There is no coverage number to hit. Do not add a gate with an invented percentage.

Do not commit API keys, caller secrets, or a filled-in `.env`. `.env.example` lists the variable names only. Provider keys are read from the environment.

## Tests that match the ADRs

- Provider behavior: WireMock in `gateway-providers` (OpenAI, Anthropic, Ollama), including 4xx, 5xx, and streaming.
- Routing and fallback: `gateway-core` unit tests and `RoutingResilienceTest`.
- Tenancy, budgets, and the ledger: `TenancyLedgerIT` (Testcontainers).
- Cache: `ResponseCacheIT` and `RedisResponseCacheIT`.
- PII: detector unit tests and `GuardrailsWebTest`.
- SDK compatibility: `EmbeddingsWebTest.officialJavaSdkCompletesChatAndEmbeddings` points `com.openai:openai-java` at the gateway.

A real Ollama model is not part of CI. `scripts/demo.sh` uses WireMock. The Ollama Compose overlay is for a manual run.

## Design constraints

- WebFlux on the request path. Do not block the event loop. BlockHound runs in tests.
- `gateway-core` must not depend on Spring Web or a provider SDK.
- One Resilience4j policy, outside the provider client. Adapters do not retry on their own.
- Usage ledger rows must not store prompt or completion text.
- Actuator stays on the management port (8081), not on 8080.
- New metrics use the `gateway.` prefix from ADR-0010. Do not add a series that ADR does not name without a new ADR.

## Pull requests

Describe the user-visible change, the tests you ran, and any ADR you added or relied on. Do not tag a release or merge to `main` unless the owner asked for that.
