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
API docs (Swagger UI): planned — M4  |  Metrics (Prometheus): planned — M5

## Status
Work in progress — see `docs/PROGRESS.md`.
