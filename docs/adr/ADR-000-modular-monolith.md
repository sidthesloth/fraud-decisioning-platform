# ADR-000: Modular monolith with event-driven seams

## Status
Accepted

## Context
Take-home submission for a Senior Back End Engineer role. Must run with `docker compose up --build`
on a reviewer's machine while demonstrating DDD, event-driven architecture, and decomposition readiness.

## Decision
One deployable application with enforced bounded contexts (ingestion, ruledomain, evaluation,
alert, decisions, api, platform). Contexts own their tables and communicate via Kafka events;
no synchronous cross-context calls on the write path.

## Consequences
- Splitting into services later requires no domain redesign: each context takes its tables + topics.
- Single process is not HA/scalable by itself; scalability comes from stateless HTTP instances +
  Kafka consumer-group partitioning.
- ArchUnit + Spring Modulith enforce boundaries; drift is a build failure, not a code-review hope.
