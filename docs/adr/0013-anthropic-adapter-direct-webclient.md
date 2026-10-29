# ADR-0013: Anthropic adapter uses a direct WebClient

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 29 Oct 2026 |
| **Deciders** | Gautam Gupta (not yet accepted) |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |
| **Amends** | [ADR-0004](./0004-provider-abstraction-spring-ai.md) for the Anthropic adapter only |

## Context

[ADR-0004](./0004-provider-abstraction-spring-ai.md) chose Spring AI behind the gateway-owned `ProviderClient` port for Anthropic. [ADR-0012](./0012-openai-adapter-direct-webclient.md) already amended that for OpenAI: a direct `WebClient` keeps the OpenAI wire format and leaves retries to Resilience4j.

Week 2 needs Anthropic chat, including SSE, translated to and from the OpenAI wire format at the gateway edge (FR-011, FR-060, API-011/012/013). Spring AI 1.1's Anthropic client does not fit that job as it stands:

- Non-streaming `chatCompletionEntity` uses a blocking `RestClient`. That fights [ADR-0003](./0003-http-stack-spring-webflux.md) (WebFlux end to end) and BlockHound (ADR-0011).
- `AnthropicChatModel` ships its own `RetryTemplate`. [ADR-0005](./0005-routing-yaml-aliases-resilience4j.md) puts retry, circuit breaking, and the time limiter outside the provider call so there is one policy.
- The OpenAI-shaped response (`id`, `finish_reason`, stream `usage`, SSE chunk shape) is rebuilt from Spring AI `ChatResponse`. The same fields are why ADR-0012 left Spring AI for OpenAI.

The `ProviderClient` port is unchanged. Spring AI types still must not leak into `gateway-core` or `gateway-api`.

## Decision drivers

- FR-011, FR-060, API-011/012/013 (Anthropic chat and SSE, still OpenAI-shaped at the edge).
- ADR-0003 (non-blocking HTTP) and ADR-0005 (one Resilience4j policy, outside the client).
- NFR-007 (WireMock contract test, no paid key).

## Considered options

1. **Spring AI `AnthropicChatModel`**, with the blocking call moved to `boundedElastic`, Spring AI retry disabled, and a mapper from `ChatResponse` back to gateway DTOs.
2. **Direct `WebClient` for Anthropic**, plus an explicit OpenAI ↔ Messages API translator. Same port as ADR-0004. Same style as ADR-0012.
3. Wait for a later Spring AI release that is reactive for both calls and has no retry of its own, and do not ship Anthropic in this milestone.

## Decision outcome

**Proposed: option 2.** Not accepted. The week-2 code follows this proposal so the milestone can be reviewed; it does not mark ADR-0004 superseded.

The Anthropic provider is a `WebClient` adapter in `gateway-providers`. It translates a gateway chat request into the Anthropic Messages API (`POST /v1/messages`, `x-api-key`, `anthropic-version`) and translates the JSON or SSE response back into gateway DTOs. Timeouts and connection failure live on that client. Retries and circuit breaking stay outside the call, per ADR-0005.

Ollama is not decided here. ADR-0004 still prefers Spring AI for Ollama when that adapter is built.

Mapping notes that belong with the adapter, not a further ADR, unless Gautam wants them called out:

- Text chat is translated. `n` greater than 1 is rejected. Non-text content parts are rejected. Tools, penalties, logprobs, and `response_format` are not forwarded.
- Anthropic requires `max_tokens`. When the client omits both `max_tokens` and `max_completion_tokens`, the adapter sends `gateway.providers.anthropic.default-max-tokens` (4096 in the shipped config).
- `stop_reason` maps to OpenAI `finish_reason` (`end_turn` → `stop`, `max_tokens` → `length`, `tool_use` → `tool_calls`).

## Consequences

- ➕ The edge stays OpenAI-shaped, the call stays non-blocking, and WireMock can assert the Anthropic JSON.
- ➕ One retry policy, the Resilience4j one from ADR-0005.
- ➖ We own the Anthropic mapping and it will drift when the Messages API adds fields we do not model (unknown fields are ignored).
- ➖ `gateway-providers` now has two WebClient adapters. Spring AI is still the written choice for Ollama until that adapter is built or this proposal is rejected.
- ➖ If this ADR is rejected, the Anthropic client should be replaced with Spring AI and the translation tests rewritten. The routing and Resilience4j work does not depend on that choice.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR-0004](./0004-provider-abstraction-spring-ai.md) — port and Ollama; Anthropic implementation is the subject of this proposal
- [ADR-0012](./0012-openai-adapter-direct-webclient.md) — the same WebClient choice for OpenAI, already Accepted
- [ADR index](./README.md)
