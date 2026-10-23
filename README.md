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
Read the p99 from `fraud_evaluation_duration_seconds` on `/actuator/prometheus`. The publish rate is approximate, and no p99 has been measured yet.

## Status
Work in progress — see `docs/PROGRESS.md`.
