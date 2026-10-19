# Fraud Decisioning Platform

Event-driven fraud rule engine: consumes categorized transaction events from Kafka, evaluates
them against versioned deterministic rules, persists immutable decisions/alerts, exposes a
secured REST API. Modular monolith, decomposition-ready.

## Architecture
<!-- TODO: insert context diagram (M0) -->
Bounded contexts: ingestion, ruledomain, evaluation, alert, decisions, api, platform.
Write path is fully asynchronous (Kafka → inbox → evaluation → outbox → publish);
REST is used only for queries. See `docs/adr/`.

## Build, run, test
```bash
./mvnw clean verify          # full gate: unit + archunit + testcontainers + integration
docker compose up --build    # app + postgres + redpanda
```
API docs: http://localhost:8080/swagger-ui.html  |  Metrics: http://localhost:8080/actuator/prometheus

## Status
Work in progress — see `docs/PROGRESS.md`.
