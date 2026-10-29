# ADR-0001: Record architecture decisions

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

SDR-0001 (APPROVED v1.0) fixes *what* the gateway must do. The *how* (stack, storage, protocols) needs a durable, reviewable record — for future contributors and for portfolio readers who want to see the reasoning, not just the code (G-011, AC-015).

## Decision drivers

- G-011: recruiter/interview-ready; trade-offs must be visible.
- AC-015: ADRs added only after SDR approval, linked from README.
- Spec-driven flow: SDR → ADR → code; nothing is implemented without an Accepted ADR.

## Considered options

1. **MADR-style ADRs in `docs/adr/`**, numbered, Markdown, reviewed via `REVIEW.md` checklist.
2. Single living design doc.
3. Decisions only in PR descriptions / GitHub issues.

## Decision outcome

**Chosen: option 1.** Each ADR uses the same template: Status, Date, Deciders, Context, Decision drivers (citing SDR IDs), Considered options, Decision outcome, Consequences, Links. Lifecycle: `Proposed → Accepted` (or `Rejected`); later changes create a new ADR that marks the old one `Superseded by ADR-NNNN` rather than editing history.

## Consequences

- ➕ Reasoning is versioned with the code and diff-reviewable.
- ➕ Each requirement is traceable to a decision (SDR IDs in drivers).
- ➖ Overhead of writing/maintaining ~10 docs; risk of drift if code changes without a superseding ADR.
- ➖ A single design doc would read more smoothly end-to-end; mitigated by the index in [README.md](./README.md).

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
