package com.acme.fraud.alert.internal;

import com.acme.fraud.alert.AlertStatus;
import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.evaluation.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;

/** Counts newly created alerts ({@code fraud.alerts.created.total}). */
public final class MeteredAlertStore implements AlertStore {

  private final AlertStore delegate;
  private final MeterRegistry registry;

  public MeteredAlertStore(AlertStore delegate, MeterRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  @Override
  public CreateResult createFor(UUID decisionId, Decision decision) {
    CreateResult result = delegate.createFor(decisionId, decision);
    if (result.created()) {
      com.acme.fraud.AfterCommit.run(
          () -> registry.counter("fraud.alerts.created.total").increment());
    }
    return result;
  }

  @Override
  public Optional<Alert> findById(UUID alertId) {
    return delegate.findById(alertId);
  }

  @Override
  public AlertPage search(
      AlertStatus status, com.acme.fraud.alert.Severity severity, int page, int size) {
    return delegate.search(status, severity, page, size);
  }

  @Override
  public Alert transition(
      UUID alertId, AlertStatus to, String actor, String note, long expectedVersion) {
    return delegate.transition(alertId, to, actor, note, expectedVersion);
  }
}
