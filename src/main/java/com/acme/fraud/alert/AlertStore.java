package com.acme.fraud.alert;

import com.acme.fraud.evaluation.Decision;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port for alert persistence. */
public interface AlertStore {

  /**
   * Creates an alert for a flagged decision plus a {@code fraud.alerts.v1} outbox row in one
   * transaction. Idempotent per decision. Non-flagged decisions are rejected.
   */
  CreateResult createFor(UUID decisionId, Decision decision);

  Optional<Alert> findById(UUID alertId);

  /**
   * Moves the alert to {@code to}, recording an audit row. Fails with {@link
   * IllegalTransitionException} for a forbidden move and {@link StaleAlertException} when {@code
   * expectedVersion} is not current (optimistic locking).
   */
  Alert transition(UUID alertId, AlertStatus to, String actor, String note, long expectedVersion);

  record CreateResult(UUID alertId, boolean created) {}

  record Alert(
      UUID alertId,
      UUID decisionId,
      Severity severity,
      AlertStatus status,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(AlertStatus from, AlertStatus to) {
      super("Illegal alert transition " + from + " -> " + to);
    }
  }

  class StaleAlertException extends RuntimeException {
    public StaleAlertException(UUID id) {
      super("Alert " + id + " was modified concurrently");
    }
  }

  class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException(UUID id) {
      super("Alert " + id + " not found");
    }
  }
}
