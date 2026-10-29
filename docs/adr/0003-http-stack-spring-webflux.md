# ADR-0003: HTTP stack: Spring WebFlux end to end

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

RD-008 made WebFlux a hard requirement. The gateway is I/O-bound: almost all latency is waiting on providers, Redis and Postgres, and it must stream SSE (FR-060, API-013) while honoring auth/limits/redaction (FR-061).

## Decision drivers

- RD-008, FR-060, FR-061, FR-062, API-013; NFR-001 (low gateway overhead); NFR-009 (avoid node affinity).

## Considered options

1. **Spring WebFlux (Reactor Netty) end to end**, reactive clients for Redis (Lettuce reactive) and HTTP (WebClient); Postgres via R2DBC or JDBC offloaded.
2. Spring MVC + `SseEmitter` on virtual threads (Java 21 Loom).
3. WebFlux at the edge, blocking internals.

## Decision outcome

**Chosen: option 1.** Functional or annotated WebFlux endpoints return `Mono`/`Flux`; streaming responses are `Flux<ServerSentEvent<ChatCompletionChunk>>` terminated with `[DONE]`. Request pipeline (auth → size limit → rate/budget → redaction → cache → route → provider → ledger) is composed as reactive `WebFilter`s / operators.

Persistence: use **R2DBC** for the hot path (ledger writes, tenant lookup cache-miss) and **JDBC only for Flyway migrations** (see ADR-0008). Any unavoidable blocking call is isolated on `Schedulers.boundedElastic()` and flagged with BlockHound in tests.

**Virtual-threads note:** option 2 is now a credible alternative — Loom removes most of the thread-per-request cost that historically motivated WebFlux. It was not selected because RD-008 fixes WebFlux, Spring AI's streaming API is `Flux`-based, and backpressure/cancellation for SSE (client disconnect → cancel upstream provider stream) is first-class in Reactor. This ADR explicitly records that Loom+MVC would also have been defensible.

## Consequences

- ➕ Natural fit for SSE, backpressure and upstream cancellation (saves provider tokens on disconnect).
- ➕ Consistent with Spring AI streaming types.
- ➖ Reactive code is harder to read, debug and stack-trace; higher contributor learning curve.
- ➖ Blocking-call leaks are easy to introduce; mitigated by BlockHound in tests.
- ➖ R2DBC + Flyway(JDBC) means two Postgres drivers on the classpath.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
