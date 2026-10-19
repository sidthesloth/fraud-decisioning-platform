# ADR-002: Redpanda locally, Kafka in production

## Status
Accepted

## Decision
Redpanda (Kafka-protocol compatible) for docker-compose and Testcontainers. Production targets
MSK or Confluent. The application depends only on the Kafka client protocol.

## Consequences
Local startup in seconds, low memory footprint, reviewer-friendly compose. Must smoke-test once
against real Apache Kafka before submission to evidence protocol compatibility.
