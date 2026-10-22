package com.acme.fraud.evaluation;

/** Sums matched rule contributions, capped at 100. */
public final class DeterministicRiskScorer implements RiskScorer {

  @Override
  public ScoreContribution score(EvaluationContext ctx, RuleEngineResult result) {
    return new ScoreContribution(Math.min(100, result.totalContribution()));
  }
}
