# ADR-0009: PII redaction: redact-and-forward pipeline with pluggable detectors

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

FR-050 requires PII redaction before provider egress; RD-006 sets the default to **redact-and-forward**, with reject-on-detect configurable (FR-052). NFR-004 requires redaction before cache writes. FR-051 caps request size.

## Decision drivers

- G-007, FR-050, FR-051, FR-052, RD-006, NFR-004, UC-007, AC-010.

## Considered options

1. **In-process pipeline of pluggable `PiiDetector`s, regex detectors first**, Presidio/NER as optional later detector.
2. Call Microsoft Presidio as a sidecar from day one.
3. No redaction; rely on provider data policies.

## Decision outcome

**Chosen: option 1.**

- Order in request pipeline: size limit (413) → **redaction** → cache lookup → provider. Cache keys/embeddings use the *redacted* text, so raw PII never reaches Redis or providers.
- `PiiDetector` SPI returns spans + entity type; built-in regex detectors: email, phone (E.164-ish), credit card (with Luhn check), IPv4/IPv6, IBAN, India-specific PAN/Aadhaar patterns (configurable on/off).
- Replacement: typed placeholders (`[EMAIL_1]`, `[PHONE_1]`), stable within a request. **No re-hydration** of responses in MVP (the mapping is never stored).
- Modes: `redact` (default) | `reject` (400 `pii_detected`) | `off`, per deployment, optionally per tenant.
- **Audit without raw PII:** ledger/metrics record only counts by entity type (`pii.redactions{type=email}`), never the matched values or their hashes. Logs never include prompt text.

## Consequences

- ➕ Zero extra infrastructure; deterministic and unit-testable.
- ➕ SPI leaves room for Presidio/NER later.
- ➖ Regex misses unstructured PII (names, addresses) and produces false positives (e.g. numbers that look like phones). This is a **baseline, not a compliance guarantee** — README must say so.
- ➖ Redaction can degrade answer quality when the model needed the value.
- ➖ Responses are not scanned in MVP (model could echo/generate PII).
- ➖ Streaming: only the prompt side is redacted (FR-061).

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
