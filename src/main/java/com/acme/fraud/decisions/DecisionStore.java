package com.acme.fraud.decisions;

import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port for persisting and reading decisions. */
public interface DecisionStore {

  /**
   * Persists the decision with its matched-rule snapshots and, in the same transaction, a {@code
   * fraud.evaluations.v1} outbox row. Idempotent per transaction id: a second call for the same
   * transaction changes nothing and reports {@code created=false}.
   */
  SaveResult save(Decision decision);

  Optional<StoredDecision> findById(UUID decisionId);

  Optional<StoredDecision> findByTransactionId(String transactionId);

  /** Paginated, indexed search (customer/outcome/time range); newest first. */
  DecisionPage search(DecisionQuery query);

  /** Whether the evaluation event of the decision has been published; empty if unknown id. */
  Optional<Boolean> evaluationPublished(UUID decisionId);

  /** Decision counts per outcome for decisions evaluated at or after {@code since}. */
  Map<Outcome, Long> countByOutcome(Instant since);

  /** Filters are optional; {@code from} inclusive, {@code to} exclusive; page is zero-based. */
  record DecisionQuery(
      String customerId, Outcome outcome, Instant from, Instant to, int page, int size) {
    public DecisionQuery {
      if (page < 0 || size < 1 || size > 200) {
        throw new IllegalArgumentException("page must be >= 0 and size within 1..200");
      }
    }
  }

  record DecisionSummary(
      UUID decisionId,
      String transactionId,
      String customerId,
      Outcome outcome,
      int score,
      boolean enrichmentDegraded,
      Instant evaluatedAt) {}

  record DecisionPage(List<DecisionSummary> items, long total) {}

  record SaveResult(UUID decisionId, boolean created) {}

  record StoredDecision(UUID decisionId, Decision decision) {}
}
