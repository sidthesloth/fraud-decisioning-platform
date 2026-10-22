# ADR-005: Alignment with the brief's core flow

## Status
Accepted

## Decisions
- Module names map to the brief: ingestion-adapter=`ingestion`, rule-domain=`ruledomain`,
  evaluation-domain=`evaluation`, alert-domain=`alert`, decision-store=`decisions`,
  retrieval-api=`api`.
- Rule versions carry an effective window (`effectiveFrom` inclusive, `effectiveTo` exclusive).
- `fraud.rules.commands.v1` (optional in the brief) is NOT built in v1: rules are managed via the
  admin REST API. It can be added later as an inbound adapter calling the same application service.
- `customer.riskTier` and `geo.velocity` are derived context fields supplied by `ContextEnricher`;
  missing values degrade like any enrichment failure.
- Replay/status: `GET /decisions/{id}/status` and `POST /dlq/replay` are added to the API surface.
