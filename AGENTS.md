# AGENTS.md — Fraud Decisioning Platform

> This file is the operating contract for any AI coding agent (Junie, Claude Code, Codex, etc.)
> working on this repository. Follow it exactly. When a task conflicts with this file,
> STOP and ask the human instead of improvising.

---

## 1. Project Identity

**Name:** Fraud Decisioning Platform
**Purpose:** Production-grade take-home submission for a Senior Back End Engineer role
(Java, Spring Boot, Kafka, PostgreSQL, event-driven/DDD, fintech).
**Brief:** Consume categorized transaction events from Kafka, evaluate them against
versioned fraud rules, persist decisions and alerts, expose everything via a secure REST API.

One-sentence architecture thesis (use in ADRs and README):

> A domain-driven, event-driven modular monolith with explicit service seams, built on
> Java 21 + Spring Boot + Kafka + PostgreSQL, that can evolve into separate services
> (fraud-ingestion, rule-evaluation, alert-management) once load and team boundaries justify it.

**Explicit non-goals (do NOT build these):**
- No ML model training. Provide a `RiskScorer` interface only, with a deterministic default.
- No Kubernetes manifests. Docker + docker-compose only.
- No multi-service split. One deployable application, enforced modularity.
- No "enterprise genius" abstractions (generic rule frameworks, plugin classloaders, etc.).

---

## 2. Technology Decisions (Fixed — Do Not Change Without an ADR)

| Concern | Decision | Rationale |
|---|---|---|
| Language | **Java 21** (`--release 21`, no preview/incubator features) | LTS, mature toolchain/container/security-scanning support; virtual threads available. Do NOT use Java 25/27 for this submission. |
| Framework | **Spring Boot 4.1.x** (see ADR-004; supersedes ADR-001 version clause) | Matches job spec; version pinned in pom, never floating. |
| Build | **Maven + wrapper** (`./mvnw`) | Lowest-friction for reviewers. |
| Messaging | **Spring Kafka**, local broker = **Redpanda** (Kafka-compatible) | Role requires Kafka; Redpanda keeps docker-compose light. Production note: MSK/Confluent. |
| Database | **PostgreSQL 16**, **Flyway** migrations | Job spec primary store. |
| Modularity | **Spring Modulith** (or strict package boundaries) + **ArchUnit** | Enforces the modular monolith; prevents the "big ball of mud" failure mode. |
| Rules | Custom lightweight deterministic rule engine (JSON-defined rules, no Drools) | Deterministic, testable, explainable in interview. |
| API | Spring WebMVC (synchronous REST) + SpringDoc OpenAPI | Simpler than WebFlux; correctness over novelty. |
| Security | Spring Security OAuth2 Resource Server (JWT), RBAC roles | Production shape without needing a real IdP (dev profile uses a test JWT issuer). |
| Resilience | **Resilience4j** (circuit breaker, bulkhead, rate limiter, retry) | Explicitly named in the job spec. |
| Observability | Micrometer + Prometheus + OpenTelemetry + structured JSON logs | `logback logstash encoder`, correlation IDs. |
| Docs | OpenAPI/SpringDoc, README with diagrams, ADRs in `/docs/adr` | The README is part of the grade. |
| Infra | Multi-stage `Dockerfile` + `docker-compose.yml` (app + postgres + redpanda) | Must run with `docker compose up --build`. |
| JSON library | **Jackson 3** (`tools.jackson.*` only — never mix with `com.fasterxml.jackson`) | See ADR-004; old packages can sneak in transitively and compile fine until they misbehave. |

---

## 3. Bounded Contexts (Package Structure)

Top-level package: `com.acme.fraud`

```
com.acme.fraud
├── ingestion          // Kafka consumer adapters, inbox, schema validation, DLT handling
│   └── internal       // implementation details — never import from other contexts
├── ruledomain         // FraudRule aggregate, rule versions, condition DSL, priority
├── evaluation         // EvaluationContext builder, RuleEngine, RiskScorer interface, Decision
├── alert              // Alert aggregate, disposition lifecycle, alert projections
├── decisions          // Decision persistence, audit trail, historical queries
├── api                // REST controllers, DTOs, mappers, security config
├── platform           // shared kernels ONLY: config, observability, errors, CloudEvents wrapper
└── bootstrap          // @SpringBootApplication, module wiring
```

Hard rules:
- `api` may call application services in other contexts; it never touches their internals.
- Contexts communicate in-process via application services/events, and across time via Kafka.
- Nothing outside `platform` may be imported by more than one context except the tiny
  shared kernel explicitly listed there. ArchUnit tests enforce this.
- No cyclic dependencies between packages. Ever.

---

## 4. Event Contract

Envelope: CloudEvents 1.0 JSON over Kafka.

```json
{
  "id": "evt_01J...",
  "type": "com.acme.payments.transaction-categorized.v1",
  "source": "//mock-producer/acme",
  "subject": "txn_123",
  "time": "2026-10-17T18:00:00Z",
  "datacontenttype": "application/json",
  "data": {
    "transactionId": "txn_123",
    "customerId": "cust_9",
    "accountId": "acc_4",
    "amount": "1250.00",
    "currency": "ZAR",
    "merchantCategory": "MCC5999",
    "merchantId": "merch_77",
    "channel": "CARD_PRESENT",
    "occurredAt": "2026-10-17T17:59:00Z",
    "location": { "country": "ZA", "city": "Cape Town" },
    "deviceId": "dev_2"
  }
}
```

Topics:

| Topic | Direction | Payload |
|---|---|---|
| `payments.transactions.categorized.v1` | consume | input events (from mock producer) |
| `fraud.evaluations.v1` | produce | full decision with matched rule versions |
| `fraud.alerts.v1` | produce | emitted only when decision in (REVIEW, DECLINE) |
| `fraud.dlq.v1` | produce | poison messages + error metadata |

Rules:
- Consumers are **idempotent by `id` (event id)**. Inbox table: `processed_events(event_id PK, processed_at)`.
- Deserialization/schema failures → `fraud.dlq.v1`, never silent drop. Max retries: 3 with backoff, then DLT.
- All outbound publishes go through the **transactional outbox**: write outbox row in the same
  DB transaction as the state change; a relay publishes and marks rows. No dual-write problem.

---

## 5. Domain Model Essentials

### FraudRule (aggregate, ruledomain)
- `ruleId`, `version` (monotonic per rule), `name`, `description`, `priority`
- `condition` (structured JSON DSL — see below), `action` (score contribution / outcome), `enabled`
- **Immutable once published.** Corrections = new version. Disable ≠ delete.

Condition DSL (deterministic, JSON-serializable, no free-form code execution):
```json
{
  "all": [
    { "field": "amount", "op": "GT", "value": "5000.00" },
    { "any": [
      { "field": "channel", "op": "EQ", "value": "CARD_NOT_PRESENT" },
      { "field": "geo.country", "op": "NOT_IN", "value": ["ZA", "NA"] }
    ]},
    { "field": "txn.lastHourCount", "op": "GT", "value": 3 }
  ]
}
```
Supported ops: `EQ, NEQ, GT, GTE, LT, LTE, IN, NOT_IN, EXISTS`. Add ops only with tests.

### EvaluationContext
Enriched view of the transaction: raw event + derived aggregates (last-hour count,
last-24h amount per account, new-device flag, cross-border flag, customer.riskTier, geo.velocity [TODO(ADAPTER): tier source and velocity definition unconfirmed]). Aggregates come from a
`ContextEnricher` port with a PostgreSQL implementation behind Resilience4j bulkhead+circuit breaker.

### Decision
- `transactionId`, `customerId`, `outcome` (APPROVE | REVIEW | DECLINE),
  `score`, `matchedRules: [{ruleId, version, contribution, reasonCode}]`,
  `inputHash` (SHA-256 of canonical event JSON), `evaluatedAt`, `engineVersion`.
- **The matched-rule snapshots are immutable and stored WITH the decision.**
  Rule edits never rewrite history. This is non-negotiable.

### Alert (alert context)
Created only for REVIEW/DECLINE. Fields: `alertId`, `decisionId`, `severity`,
`status` (OPEN | IN_REVIEW | CONFIRMED_FRAUD | FALSE_POSITIVE | CLOSED), audit of transitions.

### RiskScorer (extensibility seam)
```java
public interface RiskScorer {
    ScoreContribution score(EvaluationContext ctx, RuleEngineResult result);
}
```
Default: `DeterministicRiskScorer`. An ML implementation can be added later without touching the engine.

---

## 6. API Surface (retrieval-api)

Base path `/api/v1`. All endpoints require JWT; roles: `FRAUD_ANALYST` (read),
`FRAUD_ADMIN` (rule management). Spring Security OAuth2 resource server; a dev profile
issues HS256 test JWTs so the reviewer can call the API without an IdP (document the curl).

| Method & path | Role | Purpose |
|---|---|---|
| `GET /decisions/{id}` | ANALYST | full decision incl. matched rule versions |
| `GET /decisions?customerId=&outcome=&from=&to=&page=&size=` | ANALYST | paginated, indexed query |
| `GET /alerts?status=&severity=&page=&size=` | ANALYST | alert queue |
| `POST /alerts/{id}/disposition` | ANALYST | transition status (with optimistic locking + audit) |
| `GET /rules` / `GET /rules/{ruleId}/versions` | ANALYST | rule catalog |
| `POST /rules` / `POST /rules/{ruleId}/versions` | ADMIN | create/new version (validates DSL) |
| `POST /rules/{ruleId}/versions/{version}:activate|deactivate` | ADMIN | lifecycle |
| `GET /metrics-summary` | ANALYST | small roll-up endpoint backed by materialized counts |
| `GET /decisions/{id}/status` / `POST /dlq/replay` | ANALYST / ADMIN | processing status; replay of fraud.dlq.v1 messages (rate limited) |
| `GET /actuator/health, /metrics, /prometheus` | infra | standard |

Standards: problem-detail errors (RFC 9457), cursor-free offset pagination (fine here),
bean validation, DTOs never leak aggregates, ETags/optimistic locking on mutable resources.

---

## 7. Persistence (PostgreSQL / Flyway)

- Flyway migrations in `src/main/resources/db/migration`, **never modify an applied migration** — new file only.
- Tables (minimum): `processed_events`, `fraud_rules`, `fraud_rule_versions`, `decisions`,
  `decision_matched_rules`, `alerts`, `outbox`, `context_snapshots` (optional cache of enricher output).
- Index decisions on `(customer_id, evaluated_at)`, `(outcome, evaluated_at)`; alerts on `(status, severity)`.
- Partition `decisions` and `alerts` by month if time allows (document as a scaling note either way).
- Money = `NUMERIC(19,4)` + currency code string. **Never `double`/`float`.**
- Timestamps = `timestamptz`, stored UTC; Java side `Instant`.

---

## 8. Resilience & Failure Semantics

- Kafka consumer: manual ack after successful processing; retry 3x exponential backoff; then DLT.
- `ContextEnricher` (DB reads): Resilience4j bulkhead (limit concurrency) + circuit breaker
  (half-open probe). On failure, evaluation must still complete: mark context `enrichmentDegraded=true`,
  rules depending on enriched fields are skipped, decision annotated. **Never block evaluation on an enrichment outage.**
- Retries only on idempotent operations. POSTs that mutate carry idempotency keys where relevant.
- Rate limit the admin rule APIs (Resilience4j rate limiter) — rule changes are sensitive.
- Document failure drills in README: broker down, duplicate events, poison messages, slow DB,
  replay procedure from `fraud.dlq.v1`.

---

## 9. Security Requirements

- JWT bearer auth on every `/api/**` endpoint; actuator health/info public, metrics/prometheus
  protected (documented as such for the demo).
- RBAC as above. Return 403, not 500, on role mismatch.
- PII discipline: customer/location fields masked in logs (`cust_9`, not full profiles).
- No secrets in the repo. All config via env vars (`application.yml` + placeholders).
  Provide `application-dev.yml` with local defaults only.
- OWASP dependency-check in CI; fail build on CVSS >= 7.

---

## 10. Observability

- Structured JSON logs (logstash encoder), fields: `traceId`, `spanId`, `eventId`,
  `transactionId`, `customerId(masked)`, `outcome`, `latencyMs`.
- Correlation ID filter: read `traceparent`/CloudEvents `id`, propagate via MDC and Kafka headers.
- Metrics: `fraud.evaluations.total` (tags: outcome, engine_version),
  `fraud.rule.matched.total` (tag: rule_id+version), `fraud.alerts.created.total`,
  `fraud.evaluation.duration`, `fraud.dlq.messages.total`, `fraud.enrichment.degraded.total`.
- OpenTelemetry traces: consumer → enrichment → engine → DB → producer publish.
- Prometheus scrape endpoint enabled; provide `docker-compose` Grafana optional.

---

## 11. Testing Strategy (Definition of Done)

A task is DONE only when:

1. `./mvnw clean verify` passes **including**:
   - Unit tests (JUnit 5 + AssertJ + Mockito) — engine, DSL parser, aggregates.
   - **ArchUnit** tests — context boundaries, no cycles, no illegal cross-context imports.
   - **Testcontainers** integration tests — real PostgreSQL + Redpanda:
     consumer end-to-end (publish event → decision persisted → outbox event published),
     idempotency (same event twice → one decision), DLT path, enrichment-degraded path,
     rule versioning (old decision keeps old rule snapshot).
   - Spring `@WebMvcTest`/MockMvc or RestAssured for API + security (401/403/200 matrix).
2. **Property-based tests** (jqwik) for the rule DSL: generated conditions/values never
   throw, always return a boolean, satisfy sanity invariants.
3. Mutation testing (PIT) on `evaluation` and `ruledomain` — survive rate target ≥ 80%.
4. Load smoke test (k6 or Gatling, one scenario: 200 evals/sec for 60s) — no errors, p99 documented.
5. `spotless:check` (google-java-format), OWASP dependency-check clean.
6. New/changed behavior has a test FIRST or in the same commit.

Test data: a `mock-producer` Spring profile or small CLI runner that publishes a fixed
catalog of realistic events (normal, high-value cross-border, velocity attack, malformed → DLT).
This must work in docker-compose so a reviewer sees the whole loop running.

---

## 12. Build, Run, Verify (commands every agent must know)

```bash
./mvnw clean verify                      # full gate: unit + archunit + testcontainers + integration
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
docker compose up --build                # app + postgres + redpanda (+optional grafana)
docker compose -f docker-compose.yml up -d postgres redpanda   # infra only, run app from IDE
./mvnw spotless:apply                    # before committing
./mvnw org.pitest:pitest-maven:mutationCoverage -pl evaluation,ruledomain
```

Docker requirements:
- Multi-stage build: `maven:3.9-eclipse-temurin-21` build stage → `eclipse-temurin:21-jre` runtime.
- Non-root user in the final image, `HEALTHCHECK` against `/actuator/health`, JVM container flags
  (`-XX:MaxRAMPercentage=75.0`), explicit `JAVA_TOOL_OPTIONS` none.
- docker-compose: named volume for Postgres, Redpanda single node with advertised listeners that
  work from host AND container network, app depends_on with condition `service_healthy`.

---

## 13. Workflow Rules for Agents (Junie et al.)

1. **Plan first, code second.** Produce a short plan (files touched, design decision, test plan)
   and get approval before editing when the task touches more than one context.
2. **One context per task.** Do not refactor across bounded contexts in one go.
3. **No new dependencies** without stating why in the plan. Prefer the stdlib/Spring portfolio.
4. **Never** use preview/incubator JVM features, `var` in public APIs, Lombok, or MapStruct —
   write plain, explicit Java.
5. **Never** swallow exceptions, log-and-continue Kafka processing, or retry non-idempotent ops.
6. **Never** edit applied Flyway migrations; create `V{n}__desc.sql`.
7. **Never** commit secrets, target/, or IDE files. `.gitignore` is already correct — keep it so.
8. After every change: run the narrowest relevant test, then `./mvnw verify` before declaring done.
9. If requirements are ambiguous, ask. Do not invent business rules — put a TODO(ADAPTER) marker
   and surface the question.
10. Commit style: conventional commits (`feat(evaluation): ...`, `test(api): ...`),
    small commits, green build per commit.

---

## 14. Milestone Map (Do not reorder without approval)

- **M0 Skeleton:** Maven project, CI (GitHub Actions: verify + OWASP + spotless check), Dockerfile +
  compose skeleton, README shell, `.gitignore`, ADR-000 (modular monolith), ADR-001 (Java 21/Maven; Boot version per ADR-004),
  ADR-002 (Redpanda local / Kafka prod), ADR-003 (deterministic rules, no ML v1).
- **M1 Ruledomain:** FraudRule aggregate, version, condition DSL parser + property tests, ArchUnit baseline.
- **M2 Evaluation:** EvaluationContext, ContextEnricher port + PG impl behind Resilience4j,
  DeterministicRiskScorer, Decision + matched-rule snapshots, unit + mutation tests.
- **M3 Ingestion:** Kafka consumer, inbox dedupe, schema validation, DLT, outbox relay, Testcontainers e2e.
- **M4 API + Security:** controllers, OpenAPI, OAuth2 resource server + dev JWT issuer, RBAC tests.
- **M5 Ops:** metrics/tracing/structured logs, Grafana optional, README completion, failure-drill runbook,
  mock-producer profile, load smoke test, final `./mvnw verify` + `docker compose up --build` proof.

Status file: keep `/docs/PROGRESS.md` updated with checked boxes as milestones land.
