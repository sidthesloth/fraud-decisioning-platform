package com.acme.fraud.decisions;

import com.acme.fraud.evaluation.Decision;
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

  record SaveResult(UUID decisionId, boolean created) {}

  record StoredDecision(UUID decisionId, Decision decision) {}
}
