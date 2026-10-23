#!/usr/bin/env bash
# Load smoke: publish RATE events/sec for DURATION seconds to payments.transactions.categorized.v1
# through the compose Redpanda container (rpk), then print the decision count seen via the API.
# Usage: APP_PROFILES=dev docker compose up -d --build; TOKEN=<admin jwt> scripts/load/publish-load.sh
set -euo pipefail

RATE="${RATE:-200}"
DURATION="${DURATION:-60}"
TOPIC="payments.transactions.categorized.v1"
RUN="$(date +%s)"
TOTAL=$((RATE * DURATION))
OUT="$(mktemp)"

echo "Generating ${TOTAL} events (run ${RUN})..."
awk -v run="$RUN" -v total="$TOTAL" 'BEGIN {
  for (i = 0; i < total; i++) {
    amt = (i % 25 == 0) ? "9000.00" : "120.00"
    printf "{\"id\":\"evt_%s_%d\",\"specversion\":\"1.0\",\"type\":\"com.acme.payments.transaction-categorized.v1\",\"source\":\"//load/acme\",\"subject\":\"txn_%s_%d\",\"time\":\"2026-10-17T18:00:00Z\",\"datacontenttype\":\"application/json\",\"data\":{\"transactionId\":\"txn_%s_%d\",\"customerId\":\"cust_%d\",\"accountId\":\"acc_%d\",\"amount\":\"%s\",\"currency\":\"ZAR\",\"merchantCategory\":\"MCC5999\",\"merchantId\":\"merch_1\",\"channel\":\"CARD_NOT_PRESENT\",\"occurredAt\":\"2026-10-17T17:59:00Z\",\"location\":{\"country\":\"ZA\",\"city\":\"Cape Town\"},\"deviceId\":\"dev_%d\"}}\n", run, i, run, i, run, i, i % 500, i % 500, amt, i % 500
  }
}' > "$OUT"

echo "Publishing at ~${RATE}/s for ${DURATION}s..."
for ((s = 0; s < DURATION; s++)); do
  sed -n "$((s * RATE + 1)),$(((s + 1) * RATE))p" "$OUT" |
    docker compose exec -T redpanda rpk topic produce "$TOPIC" -f '%v\n' >/dev/null &
  sleep 1
done
wait
rm -f "$OUT"

if [[ -n "${TOKEN:-}" ]]; then
  echo "Decision counts (last 1h):"
  curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/metrics-summary?hours=1"
  echo
  echo "p99 evaluation duration: see fraud_evaluation_duration_seconds in /actuator/prometheus"
fi
