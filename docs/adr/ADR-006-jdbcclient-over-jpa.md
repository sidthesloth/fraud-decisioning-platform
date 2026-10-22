# ADR-006: JdbcClient for hot-path and aggregate access; JPA only where it clearly pays off

Status: Accepted

## Decision
- Use Spring `JdbcClient` with explicit SQL for the context enricher, decision store, outbox and inbox.
- Use JPA only where an entity aggregate genuinely benefits (optimistic locking, audit), e.g. the alert
  aggregate or rule management, and only after that need is shown. Not used so far.

## Reasons
- The enricher runs aggregate queries (count/sum/exists), not entity loads; the SQL and its index use stay visible.
- No persistence context, lazy loading or flush ordering on a path that must degrade quickly.
- `DataAccessException` maps directly to `EnrichmentUnavailableException` for the circuit breaker.
- Decision, matched-rule snapshots and outbox row are written in one explicit transaction; Flyway owns the schema.
- No new dependency; plain explicit Java as required by AGENTS.md.
