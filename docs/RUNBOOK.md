# Failure-drill runbook

Each drill states what to do, what the system is designed to do, and how to check it.
Token for the API calls: see "Calling the API (dev JWT)" in the README.

## 1. Broker down
- Do: `docker compose stop redpanda`.
- Expected: the app keeps running. The API still serves reads. New decisions are not created,
  because ingestion is Kafka-driven. Outbox rows already written stay unpublished and the
  relay logs a warning, then stops its batch so ordering is kept.
- Recover: `docker compose start redpanda`. The relay resumes from the oldest unpublished row.
  Delivery is at-least-once; consumers are idempotent by event id.
- Check: `GET /api/v1/decisions/{id}/status` shows `PERSISTED` until the evaluation is published,
  then `PUBLISHED`.

## 2. Duplicate events
- Do: publish the same CloudEvent (same `id`) twice.
- Expected: the second is a no-op. `processed_events` holds the event id, so there is one decision.
- Check: `SELECT count(*) FROM decisions WHERE transaction_id = '<txn>'` returns 1.

## 3. Poison messages
- Do: `APP_PROFILES=dev,mock-producer docker compose up --build` (the catalog includes one
  malformed message).
- Expected: an invalid message is not retried. It is written as a `fraud.dlq.v1` outbox row with the
  original message, error type, error message and attempts. Other failures are retried 3 times
  with exponential backoff, then dead-lettered. If the dead-letter write fails, the message
  is not acknowledged.
- Check: counter `fraud.dlq.messages.total` on `/actuator/prometheus` (admin token).

## 4. Slow or failing database (enrichment)
- Do: slow or block reads on `transaction_history`, or stop Postgres briefly.
- Expected: the enricher sits behind a circuit breaker and a bulkhead. On failure the decision is
  still produced with `enrichment_degraded = true`. Rules that use enriched fields are skipped
  with reason `ENRICHMENT_DEGRADED`.
- Check: counter `fraud.enrichment.degraded.total`; decisions list shows `enrichmentDegraded`.
- Note: if Postgres itself is down, the decision cannot be stored. The message is retried, then
  dead-lettered, and can be replayed (drill 5).

## 5. Replay from `fraud.dlq.v1`
- Do: fix the cause, then call as admin:
  `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"limit":50}' http://localhost:8080/api/v1/dlq/replay`
  An optional `ids` array replays specific dead letters.
- Expected: each unreplayed dead letter's original message is copied into the outbox for
  `payments.transactions.categorized.v1`. The dead letter is marked `replayedAt`, so a second call
  replays nothing. Messages that are not valid JSON objects are counted as `skipped`.
  The endpoint is rate limited (`fraud.api.dlq-replay-limit-per-second`, default 1); excess calls get 429.
- Note: a replayed event that fails again is dead-lettered again.
