# ADR-0005: Routing policy in YAML with aliases; Resilience4j retry / circuit breaker / fallback

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

Clients send `model` as an alias (`fast`, `smart`) or concrete name (FR-004). Routes need ordered fallbacks (FR-013/014) with bounded retry and circuit breaking (FR-020–022), returning 502/504 when exhausted (API-035).

## Decision drivers

- G-003, G-004, FR-004, FR-013, FR-014, FR-020, FR-021, FR-022, API-035, AC-004, AC-005.

## Considered options

1. **Spring `@ConfigurationProperties` YAML routes + Resilience4j (programmatic, per provider-model instance).**
2. Spring Cloud Circuit Breaker abstraction over Resilience4j.
3. Routing rules in the database.

## Decision outcome

**Chosen: option 1.**

```yaml
gateway:
  routes:
    fast:
      targets:
        - { provider: ollama,    model: llama3.1:8b }
        - { provider: openai,    model: gpt-4o-mini }
    smart:
      targets:
        - { provider: openai,    model: gpt-4o }
        - { provider: anthropic, model: claude-sonnet }
      timeout: 30s
      retry: { max-attempts: 2, backoff: 200ms, multiplier: 2 }
```
*(model names illustrative)*

Validated at startup (unknown provider, missing capability → fail fast). **Decorator order per target** (outermost first): `TimeLimiter → CircuitBreaker → Retry → call`; i.e. retries happen inside one breaker, and each failed retry counts toward it. The **fallback chain** sits outside all of them: on breaker open, timeout, 5xx/429-from-provider after retries, move to the next target. 4xx (other than 429) are not retried and do not fall back. One breaker per `(provider, model)` so a bad model doesn't trip a healthy provider.

Streaming: fallback is allowed only **before the first chunk** is sent to the client; after that a failure terminates the stream with an error event.

Static config only in MVP (restart to change); hot reload is future.

## Consequences

- ➕ Declarative, reviewable in git, easy to test with WireMock (AC-004/005).
- ➕ Direct Resilience4j API gives explicit control over ordering (option 2 hides it).
- ➖ Retry × fallback can multiply latency and cost (e.g. 2 targets × 2 attempts × timeout); worst-case latency must be documented per route.
- ➖ No runtime route changes without restart.
- ➖ Mid-stream failures cannot fall back transparently.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
