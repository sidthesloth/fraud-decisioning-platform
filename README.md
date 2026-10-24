# Fraud Decisioning Platform

Event-driven fraud rule engine: consumes categorized transaction events from Kafka, evaluates
them against versioned deterministic rules, persists immutable decisions/alerts, exposes a
secured REST API. Modular monolith, decomposition-ready.

## Architecture
```
                 bootstrap (wires modules)
                        |
   +-----------+--------+-----+-----------+
   |           |              |           |
  api -----> ingestion     evaluation   alert ---- decisions
   |  (application     |        |         |           |
   |   services only)  +---> ruledomain <--+-----------+
   |                              |
   +------------------------> platform (shared kernel only)
```
Dependency direction: `api` calls application services of the other contexts (never their
internals); contexts depend on `platform` for the shared kernel; no cycles (enforced by ArchUnit
and Spring Modulith tests).

Bounded contexts: ingestion, ruledomain, evaluation, alert, decisions, api, platform.
Write path is fully asynchronous (Kafka → inbox → evaluation → outbox → publish);
REST is used only for queries. See `docs/adr/`.

Module mapping to the brief: ingestion-adapter=`ingestion`, rule-domain=`ruledomain`, evaluation-domain=`evaluation`, alert-domain=`alert`, decision-store=`decisions`, retrieval-api=`api` (see ADR-005).

## Build, run, test
```bash
./mvnw clean verify          # full gate: unit + archunit + testcontainers + integration
docker compose up --build    # app + postgres + redpanda
```
API docs: `/swagger-ui.html` and `/v3/api-docs` (public)  |  Metrics: `/actuator/prometheus` (admin JWT). Logs are structured JSON outside the `dev` profile.

### Mock producer

Publishes a fixed catalog (normal, high-value cross-border, 6-event velocity burst, one malformed
message that lands on `fraud.dlq.v1`):

```bash
APP_PROFILES=dev,mock-producer docker compose up --build
```

Seeded demo rules load with the `dev` profile. Scores and thresholds are demo values.

### Calling the API (dev JWT)

The `dev` profile verifies HS256 tokens signed with `dev-only-secret-dev-only-secret-0123456789`
(override with `FRAUD_JWT_SECRET`). Mint a token with the `roles` claim
(`FRAUD_ANALYST` = read, `FRAUD_ADMIN` = rule management, DLQ replay, metrics):

```bash
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
SECRET=dev-only-secret-dev-only-secret-0123456789
H=$(printf '{"alg":"HS256","typ":"JWT"}' | b64)
P=$(printf '{"sub":"dev-admin","roles":["FRAUD_ADMIN"],"exp":%s}' $(( $(date +%s) + 3600 )) | b64)
S=$(printf '%s.%s' "$H" "$P" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64)
TOKEN="$H.$P.$S"

curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/decisions?size=5"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/prometheus
```

## Operations
Failure drills (broker down, duplicates, poison messages, slow DB, DLQ replay): `docs/RUNBOOK.md`.

### Load smoke test
Publishes 200 events/s for 60s (override with `RATE` and `DURATION`) through `rpk` in the compose Redpanda container:
```bash
APP_PROFILES=dev docker compose up -d --build
TOKEN=<admin jwt> scripts/load/publish-load.sh
```
Read the p99 from `fraud_evaluation_duration_seconds` on `/actuator/prometheus`. The publish rate is approximate.

Measured run (local Docker, dev profile, 12,000 events over ~60s): all 12,000 decisions persisted
(11,520 APPROVE, 480 REVIEW), no errors or dead letters. `fraud.evaluation.duration` p99 ≤ 1 ms
(11,939 of 12,000 under 1 ms, max 33 ms). This times the evaluation step only, not the end-to-end
Kafka-to-outbox latency.

### Tracing
OpenTelemetry tracing is enabled through `spring-boot-starter-opentelemetry`, with Kafka listener and
producer observations. Set `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (default `http://localhost:4318/v1/traces`)
and `TRACING_SAMPLING_PROBABILITY` (default 1.0). Only auto-instrumented HTTP and Kafka spans exist;
there are no custom spans for enrichment, engine or database steps.

## Known limitations and future work
- Business values are placeholders (`TODO(ADAPTER)`): score thresholds (40/80), alert severity bands, home country for cross-border. `customer.riskTier` and `geo.velocity` need a data source and are currently unavailable (null).
- The outbox relay is a single poller (no `SKIP LOCKED`). Running several instances needs batch locking or CDC.
- The input topic is keyed by whatever the producer sets. Keying by account id would give per-account ordering.
- Metrics are incremented inside the processing transaction and can overcount on rollback.
- `metrics-summary` is a live query, not a roll-up. `decisions` and `transaction_history` are not partitioned and have no retention policy.
- `fraud.rules.commands.v1` is not built (ADR-005). Categorisation is assumed upstream.
- No custom tracing spans. The dev profile uses a shared local JWT secret; production needs an IdP.

## Status
Work in progress — see `docs/PROGRESS.md`.
