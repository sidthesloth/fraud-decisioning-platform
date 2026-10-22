package com.acme.fraud.evaluation;

import com.acme.fraud.ruledomain.RuleVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application service: enrich (degrading on outage), run the rule engine, score, and decide.
 * Persistence and publishing are handled by the callers (ingestion/decisions).
 */
public final class FraudEvaluator {

  private static final Logger LOG = LoggerFactory.getLogger(FraudEvaluator.class);

  public static final String ENGINE_VERSION = "1.0.0";

  private final ContextEnricher enricher;
  private final RuleEngine engine;
  private final RiskScorer scorer;
  private final Thresholds thresholds;
  private final Clock clock;

  public FraudEvaluator(
      ContextEnricher enricher,
      RuleEngine engine,
      RiskScorer scorer,
      Thresholds thresholds,
      Clock clock) {
    this.enricher = enricher;
    this.engine = engine;
    this.scorer = scorer;
    this.thresholds = thresholds;
    this.clock = clock;
  }

  public Decision evaluate(TransactionFacts facts, List<RuleVersion> rules, String inputHash) {
    EvaluationContext context = buildContext(facts);
    Instant now = clock.instant();
    RuleEngineResult result = engine.evaluate(context, rules, facts.occurredAt());
    int score = scorer.score(context, result).points();
    return new Decision(
        facts.transactionId(),
        facts.customerId(),
        thresholds.outcomeFor(score),
        score,
        result.matched(),
        result.skipped(),
        context.enrichmentDegraded(),
        inputHash,
        now,
        ENGINE_VERSION);
  }

  private EvaluationContext buildContext(TransactionFacts facts) {
    try {
      return EvaluationContext.enriched(facts, enricher.enrich(facts));
    } catch (EnrichmentUnavailableException e) {
      LOG.warn("Enrichment unavailable, evaluating in degraded mode", e);
      return EvaluationContext.degraded(facts);
    }
  }
}
