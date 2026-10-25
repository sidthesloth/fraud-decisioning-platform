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

## How the system flows (write path)
A categorized transaction event is consumed from Kafka, evaluated against the versioned fraud
rules, stored as an immutable decision (plus an alert when flagged), and published onward.

```
Producer
  | payments.transactions.categorized.v1
  v
TransactionEventListener   (sets MDC eventId/traceId/spanId, manual ack after success)
  v
ResilientEventHandler      (3 attempts, exponential backoff; invalid events skip retries)
  |  failure ----------------------> DeadLetterSink --> outbox row (fraud.dlq.v1)
  v
TransactionEventProcessor  -- ONE DB transaction --------------------------------------+
  1. inbox claim: insert event id into processed_events (duplicate => no-op)           |
  2. FraudEvaluator                                                                    |
       enrich (last-hour count, 24h amount, new device, cross-border)                  |
         behind circuit breaker + bulkhead; on failure enrichmentDegraded=true         |
       RuleEngine: versioned rules in priority order (amount, channel, country, ...)   |
       RiskScorer: sum contributions (cap 100) -> APPROVE | REVIEW | DECLINE           |
  3. JdbcDecisionStore: decision + matched-rule snapshots + outbox row                 |
       (fraud.evaluations.v1)                                                          |
  4. JdbcAlertStore (only REVIEW/DECLINE): alert + audit entry + outbox row            |
       (fraud.alerts.v1)                                                               |
  5. transaction_history update (feeds future velocity/aggregate features)             |
  +-------------------------------------------------------------------------------------+
  v
OutboxRelay (scheduled poller, ordered by created_at, id) --> Kafka
  fraud.evaluations.v1 | fraud.alerts.v1 | fraud.dlq.v1
```

1. **Intake.** `TransactionEventListener` copies the record key (`eventId`) and the `traceparent`
   header (`traceId`, `spanId`) into the logging context (MDC) so every log line for the message is
   correlated, and acknowledges manually only after processing has settled. A crash or rebalance
   causes redelivery, never loss.
2. **Retry and dead letter.** `ResilientEventHandler` retries up to 3 times with exponential
   backoff. Unparseable or invalid events go straight to `DeadLetterSink`, as do events that exhaust
   retries. The dead letter is an outbox row carrying the original message, the error and the
   attempt count. If that write fails, the message is not acked and Kafka redelivers it.
3. **Atomic processing.** Inbox claim, evaluation, decision, alert and outbox rows commit or roll
   back together, including the inbox claim, so a retry starts clean. The inbox makes consumption
   idempotent by event id.
4. **Evaluation.** Rules are applied per transaction on different criteria (amount, channel,
   country, velocity). If enrichment is unavailable, evaluation still completes, rules that need
   enriched fields are skipped, and the decision is annotated as degraded.
5. **Storage.** Matched-rule snapshots (rule id, version, contribution, reason code) are stored
   with the decision, so later rule edits never rewrite history.
6. **Publishing.** Outbox rows are written in the same transaction as the state change (no
   dual write). `OutboxRelay` marks a row published only after the broker acknowledges and stops at
   the first failure to keep order. Delivery is at-least-once; idempotent consumers make the effect
   exactly-once.

The read path is separate: the REST API only queries stored decisions, alerts and rules.
Failure behaviour for each stage is exercised in `docs/RUNBOOK.md`.

## Build, run, test
```bash
./mvnw clean verify          # full gate: unit + archunit + testcontainers + integration
docker compose up --build    # app + postgres + redpanda
```
API docs: `/swagger-ui.html` and `/v3/api-docs` (public)  |  Metrics: `/actuator/prometheus` (admin JWT). Logs are structured JSON outside the `dev` profile.

### Confirm it works
- **Tests:** `./mvnw clean verify` must end with `BUILD SUCCESS` (Docker must be running for the Testcontainers tests). Per-test results are in `target/surefire-reports/` and `target/failsafe-reports/`.
- **Health:** `curl localhost:8080/actuator/health` returns `{"status":"UP"}`.
- **Public API docs (no token needed):**
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/v3/api-docs        # 200
  curl -s -o /dev/null -w "%{http_code}\n" -L localhost:8080/swagger-ui.html # 200 (redirects to /swagger-ui/index.html)
  ```
  Or open <http://localhost:8080/swagger-ui.html> in a browser.
- **Protected endpoints stay protected:** `curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/v1/decisions` returns `401` without a token (see *Calling the API* for a dev JWT).

### Tear down
```bash
docker compose down        # stop and remove containers, keep the Postgres volume (data survives)
docker compose down -v     # also delete the pgdata volume (clean slate)
```
If you started with a profile (e.g. `APP_PROFILES=dev,mock-producer`), no need to repeat it for `down`.

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

`docker-compose.yml` sets sampling to 0.0 and disables OTLP trace and metric export (`Failed to publish metrics to OTLP receiver` otherwise), because no collector runs there
(otherwise the app logs `Failed to export spans ... localhost:4318`). To trace, run a collector and set
`TRACING_SAMPLING_PROBABILITY=1.0`, `OTEL_EXPORT_ENABLED=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.

### Production configuration
Compose defaults are for local demo only. For a real deployment set `POSTGRES_USER` / `POSTGRES_PASSWORD`
(from a secrets manager), run without the `dev` profile (`APP_PROFILES=prod`) and point
`spring.security.oauth2.resourceserver.jwt.issuer-uri` at your IdP instead of the shared HS256 secret.

## Known limitations and future work
- Business values are placeholders (`TODO(ADAPTER)`): score thresholds (40/80), alert severity bands, home country for cross-border. `customer.riskTier` and `geo.velocity` need a data source and are currently unavailable (null).
- The outbox relay locks each batch with `FOR UPDATE SKIP LOCKED`, so several instances can run safely. Ordering is per batch, not global across instances; strict global ordering would need a single relay or partitioned keys.
- The input topic is keyed by whatever the producer sets. Keying by account id would give per-account ordering.
- Evaluation, alert and dead-letter counters are incremented after the transaction commits (`AfterCommit`), so rolled-back work is not counted.
- `metrics-summary` is a live query, not a roll-up. `decisions` and `transaction_history` are not partitioned and have no retention policy.
- `fraud.rules.commands.v1` is not built (ADR-005). Categorisation is assumed upstream.
- No custom tracing spans. The dev profile uses a shared local JWT secret; production needs an IdP.

## Status
Milestones M0–M5 complete (see `docs/PROGRESS.md`). Open items are listed under *Known limitations and future work*; business values marked `TODO(ADAPTER)` await confirmation rather than being invented.
