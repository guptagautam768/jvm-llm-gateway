# ADR-0014: Guardrail limits, placeholder identity, and the week-5 metric extensions

| Field | Value |
|---|---|
| **Status** | Proposed |
| **Date** | 31 Oct 2026 IST |
| **Deciders** | Gautam Gupta (pending review) |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

[ADR-0009](./0009-pii-redaction-pipeline.md) and [ADR-0010](./0010-observability-and-operations.md) are Accepted and this week implements them. A few operational choices are not fixed by those ADRs. This record states them so they are not silent. Nothing here changes redact-and-forward, the detector list, the management port, or the Micrometer prefix.

## Decision drivers

- FR-051, FR-052, NFR-004, NFR-006, ADR-0009, ADR-0010.

## Considered options

1. **Document the gaps as a Proposed ADR and implement them.** Leave ADR-0009 and ADR-0010 Accepted.
2. Edit the Accepted ADRs in place.
3. Pick the values in code and mention them only in the README.

## Decision outcome

**Chosen: option 1.** Proposed until Gautam accepts or replaces it.

- **Request size (FR-051).** The check runs on the parsed chat request, before redaction. The measured size is the UTF-8 length of the text the redactor scans: message content (a string or `text` parts), the message `name`, and tool-call `arguments` strings. Default `gateway.guardrails.max-request-bytes` is **1 MiB** (1048576). Over the limit is **413** `request_too_large`. A non-positive limit turns the check off. The 2MB codec cap stays as a separate transport limit.
- **Placeholder identity.** Inside one request, the same entity type and the same matched text share one placeholder (`[EMAIL_1]`). Indexes increment in first-seen order per type. The same value later in the request reuses the placeholder and still counts as another occurrence. The mapping is not stored and is not stable across requests. Responses are not re-hydrated (ADR-0009).
- **Overlapping spans.** The longer span wins. Equal length uses a fixed priority: credit card, Aadhaar, IBAN, PAN, IPv6, IPv4, phone, email.
- **Checksums beyond Luhn.** ADR-0009 requires Luhn for cards and names the other patterns. This implementation also requires Verhoeff for Aadhaar (UIDAI check digit, first digit 2–9), the mod-97 check for IBAN, and a PAN whose fourth character is a holder type (`P`, `C`, `H`, `F`, `A`, `T`, `B`, `L`, `J`, `G`). These cuts reduce false positives. They are still a regex baseline, not a compliance guarantee.
- **Phone formats.** E.164-ish values are a leading `+`, 8–15 digits, with single spaces, dots, or dashes. Indian numbers add mobiles `[6-9]` plus nine digits (optional trunk `0`, or one separator as in `98765 43210`) and 11-digit STD numbers that start with `0`.
- **Per-tenant mode.** `gateway.guardrails.mode` is `redact` (default), `reject`, or `off`. `gateway.guardrails.tenants.<tenant-id>.mode` overrides it. This is configuration, not a column on the ADR-0008 tenant table.
- **Audit store.** Counts go to `gateway.pii.redactions{type}` and to one info log line of `type=count`. The usage ledger is not given a new column. ADR-0008 rows still have no prompt, no completion, and no matched text.
- **Extra series.** The week-5 task asks for provider errors and fallbacks. ADR-0010 does not name them. This adds `gateway.provider.errors` (`provider`, `kind`) and `gateway.fallbacks` (`from`, `to`). `kind` is a fixed set (`timeout`, `unavailable`, `upstream_5xx`, `upstream_429`, and the other provider failure kinds). A retried attempt is not a second error; the failed target is one increment. Tenant tags stay opt-in, including on `gateway.pii.redactions`.
- **Trace export.** Sampling defaults to 1.0 so a local trace is not dropped. OTLP export defaults to **off** (`OTEL_TRACES_EXPORT_ENABLED=false`) so a process without a collector does not log connection errors. The Compose `observability` profile turns export on and points it at the collector. Jaeger is the trace UI in that profile; ADR-0010 allows Jaeger or Tempo.

## Consequences

- ➕ The size limit, placeholder rule, and checksums are testable and written down.
- ➕ The ledger schema from ADR-0008 does not change.
- ➖ 1 MiB and the checksum rules are judgements. Reject this ADR to change them.
- ➖ Regex still misses names and addresses and still false-positives on some numbers (ADR-0009).
- ➖ OTLP stays dark until export is enabled, including in the default Compose file.

## Links

- [ADR-0009](./0009-pii-redaction-pipeline.md)
- [ADR-0010](./0010-observability-and-operations.md)
- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
