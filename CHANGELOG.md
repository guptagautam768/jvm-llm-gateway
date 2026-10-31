# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.0.0] - 2026-10-31

First release. Weeks 1–5 (OpenAI and Anthropic WebClient adapters, YAML aliases and Resilience4j, tenancy, rate limits, budgets, the usage ledger, exact and semantic Redis cache, PII redaction, and `gateway.*` metrics; ADR-0001 through ADR-0014) plus the additions below.

### Added

- `POST /v1/embeddings` with the same caller auth, rate limits, budgets, usage ledger, PII redaction, and `gateway.*` metrics as chat. Alias routing applies. Embeddings responses are not cached.
- Ollama WebClient adapter for chat, NDJSON streaming translated to OpenAI SSE, and `/api/embed`. Configurable `OLLAMA_BASE_URL`. Optional `OLLAMA_API_KEY`. Zero-cost pricing unless a price row overrides it.
- `embed` alias (OpenAI `text-embedding-3-small`, then Ollama `nomic-embed-text`) and an `ollama` Spring profile plus `docker-compose.ollama.yml` for a run with no paid keys.
- Semantic-cache embeddings go through the configured provider (`gateway.cache.semantic.embedding-provider`), so the Ollama profile can embed without an OpenAI key.
- Startup checks for absolute provider URLs, timeouts, cache bounds, and the semantic embedding provider.
- OpenAI-style JSON for 404, 405, and 415. Ollama errors are rewritten into that shape.
- Ledger drain on shutdown (up to 5 seconds), after the existing graceful server shutdown.
- Official OpenAI Java SDK contract test for chat and embeddings (WireMock behind the gateway).
- `scripts/demo.sh`, this changelog, `CONTRIBUTING.md`, and [v1.0.0 release notes](docs/releases/v1.0.0.md).
- [ADR-0015](docs/adr/0015-ollama-webclient-and-public-embeddings.md) (Accepted).

### Changed

- README is the go-live overview: architecture, features, quickstart, curl, and the ADR index.
