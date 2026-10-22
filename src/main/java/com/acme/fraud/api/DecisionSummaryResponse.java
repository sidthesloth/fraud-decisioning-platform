package com.acme.fraud.api;

import com.acme.fraud.decisions.DecisionStore.DecisionSummary;
import java.time.Instant;
import java.util.UUID;

record DecisionSummaryResponse(
    UUID decisionId,
    String transactionId,
    String customerId,
    String outcome,
    int score,
    boolean enrichmentDegraded,
    Instant evaluatedAt) {

  static DecisionSummaryResponse from(DecisionSummary s) {
    return new DecisionSummaryResponse(
        s.decisionId(),
        s.transactionId(),
        s.customerId(),
        s.outcome().name(),
        s.score(),
        s.enrichmentDegraded(),
        s.evaluatedAt());
  }
}
