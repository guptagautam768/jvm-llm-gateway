# ADR-0015: Ollama WebClient adapter and the public embeddings route

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 31 Oct 2026 |
| **Deciders** | Gautam Gupta (not yet accepted) |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |
| **Amends** | [ADR-0004](./0004-provider-abstraction-spring-ai.md) for the Ollama adapter only |

## Context

[ADR-0004](./0004-provider-abstraction-spring-ai.md) still names Spring AI as the Ollama implementation. [ADR-0012](./0012-openai-adapter-direct-webclient.md) and [ADR-0013](./0013-anthropic-adapter-direct-webclient.md) already left Spring AI for OpenAI and Anthropic: a direct `WebClient` stays non-blocking (ADR-0003), keeps Resilience4j as the only retry policy (ADR-0005), and is what WireMock asserts. Spring AI's Ollama client has the same two problems ADR-0013 recorded for Anthropic: a blocking `RestClient` and a `RetryTemplate` of its own.

Week 6 also exposes `POST /v1/embeddings` (FR-002, API-020/021/022). Auth, rate limits, budgets, the ledger, PII redaction, and `gateway.*` metrics apply as they do for chat. A few operational choices are not fixed by the accepted ADRs. This record states them so they are not silent. The week-6 code follows this proposal so the milestone can be reviewed. It does not mark ADR-0004 superseded.

## Decision drivers

- FR-002, FR-010, FR-012, FR-060, API-020/021/022, UC-002, UC-003, NFR-008.
- ADR-0003 (non-blocking HTTP), ADR-0005 (one Resilience4j policy, capability checks), ADR-0006 (chat cache only), ADR-0007 (budgets), ADR-0009 (redact before egress).

## Considered options

1. **Spring AI `OllamaChatModel` / `OllamaEmbeddingModel`**, with the blocking call moved to `boundedElastic` and Spring AI retry disabled.
2. **Direct `WebClient` against Ollama's native HTTP API** (`POST /api/chat`, including NDJSON streaming, and `POST /api/embed`), translated to the OpenAI wire format at the gateway edge. Same port as ADR-0004. Same style as ADR-0012 and ADR-0013.
3. Call Ollama's OpenAI-compatibility shim (`/v1/chat/completions`, `/v1/embeddings`) and reuse the OpenAI adapter unchanged.

## Decision outcome

**Proposed: option 2.** Not accepted.

The Ollama provider is a `WebClient` adapter in `gateway-providers`. The base URL is `gateway.providers.ollama.base-url` (`OLLAMA_BASE_URL`, default `http://127.0.0.1:11434`). No API key is required. If `OLLAMA_API_KEY` is set, the adapter sends it as `Authorization: Bearer`. Timeouts live on that client. Retries and circuit breaking stay outside the call, per ADR-0005.

Mapping notes that belong with the adapter, unless this proposal is rejected:

- Text chat is translated, including `temperature`, `top_p`, `stop`, and `max_tokens` / `max_completion_tokens` (`options.num_predict`). `n` greater than 1 is rejected. Non-text content parts are rejected. Tools, penalties, and `response_format` are not forwarded.
- Native streaming is newline-delimited JSON. The adapter turns that into OpenAI chat chunks. The gateway edge still writes SSE and `data: [DONE]`.
- Embeddings use `/api/embed` (`input` as a string or an array). The response is rewritten to OpenAI's `data[].embedding` shape. `dimensions` is forwarded when the client sends it. `encoding_format` and `user` are ignored (FR-001's ignore option).
- Ollama errors are rewritten into the OpenAI error body before they leave the adapter, as Anthropic's errors already are.

The public embeddings route, in the same proposal:

- `POST /v1/embeddings` requires a caller key and walks the same alias table as chat. A target whose provider cannot embed (Anthropic) is skipped. That skip is not a provider error and does not increment `gateway.fallbacks`. If no target can embed, the client gets **501** `not_implemented`. Startup still fails on an unknown provider or a target that cannot chat (ADR-0005). It does **not** fail because a chat alias includes Anthropic: those aliases are shared, and ADR-0004 already says Anthropic has no embeddings.
- The pipeline is the ADR-0009 order: size limit, then redaction, then the provider. There is no response cache. ADR-0006 scopes exact and semantic cache to non-streaming chat. Embeddings do not read or write it and do not send `X-Cache`.
- The size check is the UTF-8 length of the input strings (a string, or an array of strings). The limit is the same `gateway.guardrails.max-request-bytes` (ADR-0014). Over the limit is **413** `request_too_large`.
- Redaction uses the same detectors, placeholder rule, and per-tenant mode as chat. The provider sees the redacted input. Vectors are not scanned.
- Budgets: the pre-flight estimate is input characters (about 4 per token) and no reserved completion. The post-call reconcile uses provider usage when present, otherwise the estimate. The request bucket and the tokens-per-minute bucket apply.
- Prices: an explicit `gateway.prices` row wins. A model with no row that was served by Ollama is **0 USD**, not a missing price, so `gateway.price.missing` does not increment and the ledger stores `0` rather than null. Any other provider with no row stays null and increments the metric (FR-071).
- Metrics use the existing ADR-0010 names with `route=embeddings`. No new series.
- Semantic-cache embeddings go through `ProviderClient.embed` for `gateway.cache.semantic.embedding-provider` (ADR-0006's configurable embedding route). The default stays OpenAI `text-embedding-3-small` at 1536 dimensions. A missing key makes semantic lookup a miss; it does not fail startup. An unknown provider id does fail startup.

Startup validation that this proposal adds, beyond ADR-0005's route checks: provider base URLs must be absolute `http` or `https`, timeouts and cache bounds must be positive, and the semantic embedding provider id must exist and report an embeddings capability when semantic cache is enabled.

Graceful shutdown stays `server.shutdown=graceful` with a 20s phase. On context close the async ledger drain waits up to 5 seconds. That wait is shutdown only; the request path does not block on the ledger (ADR-0008).

## Consequences

- ➕ Local chat, streaming, and embeddings work without a paid key, and WireMock can assert the native Ollama JSON.
- ➕ One retry policy, and the edge stays OpenAI-shaped.
- ➕ Anthropic can stay on a chat alias without making embeddings configuration illegal.
- ➖ We own the Ollama mapping. Unknown fields are ignored, and the native API can drift.
- ➖ Skipping a target that cannot embed is easy to confuse with fallback. It is a different counter on purpose.
- ➖ Ollama's zero price is a default, not an invoice. An explicit price row overrides it.
- ➖ Embeddings are not cached. A repeated embeddings call always hits a provider unless some later ADR says otherwise.
- ➖ If this ADR is rejected, the Ollama client should be replaced and the public route's skip rule rewritten. Chat routing for OpenAI and Anthropic does not depend on that choice.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR-0004](./0004-provider-abstraction-spring-ai.md) — port; Ollama implementation is the subject of this proposal
- [ADR-0012](./0012-openai-adapter-direct-webclient.md), [ADR-0013](./0013-anthropic-adapter-direct-webclient.md) — the same WebClient choice, already Accepted
- [ADR-0006](./0006-caching-redis-exact-and-semantic.md) — chat cache; embeddings responses are out of scope
- [ADR index](./README.md)
