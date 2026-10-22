package com.acme.fraud.decisions.internal;

import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.MatchedRule;
import com.acme.fraud.evaluation.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;

/** Records evaluation metrics when a decision is newly stored (duplicates are not counted). */
public final class MeteredDecisionStore implements DecisionStore {

  private final DecisionStore delegate;
  private final MeterRegistry registry;

  public MeteredDecisionStore(DecisionStore delegate, MeterRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  @Override
  public SaveResult save(Decision decision) {
    SaveResult result = delegate.save(decision);
    if (result.created()) {
      registry
          .counter(
              "fraud.evaluations.total",
              "outcome",
              decision.outcome().name(),
              "engine_version",
              decision.engineVersion())
          .increment();
      for (MatchedRule m : decision.matchedRules()) {
        registry
            .counter("fraud.rule.matched.total", "rule_id", m.ruleId() + "@" + m.version())
            .increment();
      }
      if (decision.enrichmentDegraded()) {
        registry.counter("fraud.enrichment.degraded.total").increment();
      }
    }
    return result;
  }

  @Override
  public Optional<StoredDecision> findById(UUID decisionId) {
    return delegate.findById(decisionId);
  }

  @Override
  public Optional<Boolean> evaluationPublished(UUID decisionId) {
    return delegate.evaluationPublished(decisionId);
  }

  @Override
  public java.util.Map<Outcome, Long> countByOutcome(java.time.Instant since) {
    return delegate.countByOutcome(since);
  }

  @Override
  public DecisionPage search(DecisionQuery query) {
    return delegate.search(query);
  }

  @Override
  public Optional<StoredDecision> findByTransactionId(String transactionId) {
    return delegate.findByTransactionId(transactionId);
  }
}
