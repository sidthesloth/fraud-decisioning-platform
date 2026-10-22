package com.acme.fraud.evaluation;

/** Extensibility seam: an ML-backed scorer can replace the deterministic default. */
public interface RiskScorer {

  ScoreContribution score(EvaluationContext ctx, RuleEngineResult result);
}
