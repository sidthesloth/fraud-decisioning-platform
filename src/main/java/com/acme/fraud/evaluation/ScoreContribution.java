package com.acme.fraud.evaluation;

/** Score produced by a {@link RiskScorer}, in the range 0..100. */
public record ScoreContribution(int points) {

  public ScoreContribution {
    if (points < 0 || points > 100) {
      throw new IllegalArgumentException("points must be within 0..100");
    }
  }
}
