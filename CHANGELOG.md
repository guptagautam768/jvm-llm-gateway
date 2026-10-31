# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project does not yet have a git tag. Versions below describe the milestone, not a published release.

## [Unreleased]

### Added

- `POST /v1/embeddings` with the same caller auth, rate limits, budgets, usage ledger, PII redaction, and `gateway.*` metrics as chat. Alias routing applies. Embeddings responses are not cached.
- Ollama WebClient adapter for chat, NDJSON streaming translated to OpenAI SSE, and `/api/embed`. Configurable `OLLAMA_BASE_URL`. Optional `OLLAMA_API_KEY`. Zero-cost pricing unless a price row overrides it.
- `embed` alias (OpenAI `text-embedding-3-small`, then Ollama `nomic-embed-text`) and an `ollama` Spring profile plus `docker-compose.ollama.yml` for a run with no paid keys.
- Semantic-cache embeddings go through the configured provider (`gateway.cache.semantic.embedding-provider`), so the Ollama profile can embed without an OpenAI key.
- Startup checks for absolute provider URLs, timeouts, cache bounds, and the semantic embedding provider.
- OpenAI-style JSON for 404, 405, and 415. Ollama errors are rewritten into that shape.
- Ledger drain on shutdown (up to 5 seconds), after the existing graceful server shutdown.
- Official OpenAI Java SDK contract test for chat and embeddings (WireMock behind the gateway).
- `scripts/demo.sh`, `CHANGELOG.md`, `CONTRIBUTING.md`, and a v1.0.0 release-notes draft.
- [ADR-0015](docs/adr/0015-ollama-webclient-and-public-embeddings.md) (Proposed).

### Changed

- README is the go-live overview: architecture, features, quickstart, curl, and the ADR index. Week-by-week notes moved out of the front page.

## [0.1.0] - weeks 1–5, untagged

Shipped on `main` before the week-6 branch: OpenAI and Anthropic WebClient adapters, YAML aliases and Resilience4j, tenancy, rate limits, budgets, the usage ledger, exact and semantic Redis cache, PII redaction, and `gateway.*` metrics (ADR-0001 through ADR-0014, Accepted).
