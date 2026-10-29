# ADR-0010: Observability and ops: Micrometer, OpenTelemetry, Prometheus/Grafana, actuator on management port

| Field | Value |
|---|---|
| **Status** | Accepted (approved by Gautam Gupta, 29 Oct 2026) |
| **Date** | 29 Oct 2026 IST |
| **Deciders** | Gautam Gupta |
| **Requirements** | [SDR-0001 v1.0](../sdr/0001-jvm-llm-gateway-sdr.md) |

## Context

FR-073 lists required metrics; FR-074 asks for OTel traces; NFR-006 asks for a Grafana dashboard and structured logs; RD-004 / FR-072 require actuator **only on a separate management port**.

## Decision drivers

- G-009, FR-034, FR-072, FR-073, FR-074, NFR-006, RD-004, CFG-009, AC-013.

## Considered options

1. **Micrometer (metrics + Observation API) → Prometheus; Micrometer Tracing OTel bridge → OTLP collector; Grafana dashboards in repo.**
2. OpenTelemetry Java agent for everything.
3. Vendor APM.

## Decision outcome

**Chosen: option 1.**

- `server.port=8080` (API), `management.server.port=8081` (actuator). Exposed on management: `health` (liveness/readiness groups), `info`, `prometheus`, `metrics`. Never `env`, `configprops`, `heapdump` in default profile. Compose maps 8081 only to localhost.
- Metrics (prefix `gateway.`): `requests` (route, provider, model, status), `provider.latency` timer, `tokens` (type=prompt|completion), `cost.estimated`, `cache` (result=hit|miss, type=exact|semantic), `ratelimit.rejections` (reason), `pii.redactions` (type), `price.missing`. **Tenant as a tag is opt-in** (cardinality).
- Traces: Observation spans for auth, cache, redaction, provider call; context propagated through Reactor (`context-propagation`). OTLP exporter to an OTel Collector → Jaeger/Tempo in compose.
- Logs: JSON (Boot 3.4+ structured logging), with `traceId`/`requestId`; no prompt content.
- Repo ships `ops/grafana/gateway-dashboard.json` and Prometheus scrape config.

## Consequences

- ➕ Spring-native, no agent; consistent metric names for dashboards and AC-013 tests.
- ➕ Management-port isolation reduces accidental exposure.
- ➖ Separate management port complicates some PaaS deployments with single exposed port (demo hosting may need tweaks).
- ➖ Reactor context propagation is a known source of lost trace context; needs explicit tests.
- ➖ Running Prometheus + Grafana + collector makes the local compose heavier; put them behind a compose profile (`--profile observability`).

## Links

- [SDR-0001](../sdr/0001-jvm-llm-gateway-sdr.md)
- [ADR index](./README.md)
