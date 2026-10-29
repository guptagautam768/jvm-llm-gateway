# ADR-0004: Provider abstraction via Spring AI (OpenAI, Anthropic, Ollama)

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026). Partially superseded by [ADR-0012](./0012-openai-adapter-direct-webclient.md): the OpenAI adapter is a direct WebClient. The `ProviderClient` port, and Spring AI for other providers, still stand. |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

MVP providers are fixed: OpenAI (FR-010), Anthropic (FR-011, RD-001), Ollama (FR-012). Bedrock is future (F-002). The gateway needs one internal contract for chat (sync + streaming) and embeddings, plus normalized token usage for the ledger (FR-070).

## Decision drivers

- G-002, FR-010/011/012, FR-070 (token usage), FR-060 (streaming), NFR-008 (Ollama local path), F-002 (easy to add providers later).

## Considered options

1. **Spring AI `ChatModel` / `StreamingChatModel` / `EmbeddingModel`** behind a thin gateway-owned `ProviderClient` port.
2. Hand-written `WebClient` adapters per provider REST API.
3. LangChain4j.

## Decision outcome

**Chosen: option 1, wrapped.** `gateway-core` defines its own port (`ProviderClient`: `chat`, `stream`, `embed`, `capabilities`) using gateway DTOs; `gateway-providers` implements it with Spring AI starters for OpenAI, Anthropic and Ollama. Spring AI types never leak into core or API modules. Provider instances are created per configured provider entry (base URL, key ref, timeouts), not just the auto-configured singleton beans.

Capabilities matrix (MVP): OpenAI — chat, stream, embeddings; Anthropic — chat, stream (no embeddings; embedding routes must target OpenAI or Ollama); Ollama — chat, stream, embeddings if the model supports it.

If Spring AI lacks a field the OpenAI-compatible API needs (API-011/012), the adapter may drop to the provider's raw client for that case — documented per field.

## Consequences

- ➕ Less HTTP/serialization code; Spring-ecosystem signal for the portfolio.
- ➕ Own port isolates us from Spring AI API churn (it moves fast between minor versions) and makes WireMock/fake providers trivial.
- ➖ Mapping OpenAI shape → Spring AI → provider and back may lose fidelity (e.g. usage fields in streams, `finish_reason` names); needs contract tests per provider.
- ➖ Anthropic has no embeddings — routing config must validate capability at startup.
- ➖ An extra abstraction layer vs. calling Spring AI directly.

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR-0012](./0012-openai-adapter-direct-webclient.md) — amends the OpenAI implementation only (WebClient, not Spring AI). This ADR's history is unchanged.
- [ADR index](./README.md)
