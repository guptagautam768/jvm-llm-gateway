# ADR-0012: OpenAI adapter uses a direct WebClient

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 2026-10-29 |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |
| **Amends** | [ADR-0004](./0004-provider-abstraction-spring-ai.md) for the OpenAI adapter only |

## Context

[ADR-0004](./0004-provider-abstraction-spring-ai.md) chose Spring AI `ChatModel` / `StreamingChatModel` / `EmbeddingModel` behind a gateway-owned `ProviderClient` port, including for OpenAI. The week-1 milestone is an OpenAI-compatible passthrough: `POST /v1/chat/completions` (JSON and SSE) and `GET /v1/models` must keep the OpenAI wire format (FR-001, FR-060, API-011/012/013) so a WireMock test can prove the gateway forwards the request and returns the response without calling the real API.

Spring AI's chat abstraction reshapes that format. Fields such as `id`, `created`, `system_fingerprint`, stream `usage`, and raw SSE chunk shape are easy to drop or rename on the way through `ChatModel`. ADR-0004 already allowed a raw client when Spring AI lacks a field. For OpenAI that exception is the whole adapter, not a field or two. Gautam reviewed the implementation and kept the WebClient adapter.

The `ProviderClient` port itself is unchanged. Anthropic (week 2) and Ollama may still be Spring AI adapters behind it. Spring AI types still must not leak into `gateway-core` or `gateway-api`.

## Decision drivers

- FR-001, FR-010, FR-060, API-011/012/013 (wire-format fidelity for chat and SSE).
- NFR-007 (WireMock contract test without a paid key).
- ADR-0004's port boundary and the plan to add Anthropic and Ollama without a second port.

## Considered options

1. **Keep ADR-0004 as written for OpenAI:** Spring AI `ChatModel` / `StreamingChatModel`, and document each dropped field.
2. **Direct `WebClient` for OpenAI only**, behind the existing `ProviderClient`. Other providers may still use Spring AI.
3. **WebClient for every provider** and drop Spring AI from the design.

## Decision outcome

**Chosen: option 2.** The OpenAI provider is a `WebClient` adapter in `gateway-providers`. It serializes gateway DTOs to the OpenAI REST API and parses the JSON or SSE response back into those DTOs. Timeouts and connection failure live on that client. `ProviderClient` (`complete`, `stream`, `embed`, `capabilities`, and `listModels`) stays the only type core depends on.

Anthropic and Ollama are not decided here. They may use Spring AI starters behind the same port, as ADR-0004 describes, when those adapters are built.

## Consequences

- ➕ OpenAI request and response fields round-trip, including SSE chunks and upstream error bodies. WireMock can assert the forwarded JSON.
- ➕ The port from ADR-0004 still isolates core from both WebClient and any future Spring AI types.
- ➖ We own OpenAI request/response mapping, error mapping, and HTTP timeouts. That duplicates part of what Spring AI's OpenAI client already does, and it will drift if the OpenAI API adds fields we do not model (unknown fields are ignored; see FR-001).
- ➖ Retries and circuit breaking are not inside this client. They stay outside the call, per ADR-0005, so OpenAI does not grow a second retry policy beside Resilience4j.
- ➖ Two implementation styles in `gateway-providers` (WebClient for OpenAI, Spring AI allowed for others) until a later ADR collapses them.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR-0004](./0004-provider-abstraction-spring-ai.md) — port and non-OpenAI providers; OpenAI implementation amended by this ADR
- [ADR index](./README.md)
