# ADR-003: Deterministic rule engine, no ML in v1

## Status
Accepted

## Decision
JSON-defined deterministic rules with immutable versioned snapshots stored per decision.
`RiskScorer` interface exists as the future ML seam; default implementation is deterministic.

## Consequences
Decisions are explainable, testable (property-based + mutation testing), and audit-safe.
Historical decisions never change meaning when rules evolve — snapshots are immutable.
