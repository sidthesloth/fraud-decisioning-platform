package com.acme.fraud.ruledomain;

/** What a matching rule contributes: a non-negative score and a stable reason code. */
public record RuleAction(int scoreContribution, String reasonCode) {

  public RuleAction {
    if (scoreContribution < 0) {
      throw new RuleDslException("Score contribution must not be negative");
    }
    if (reasonCode == null || reasonCode.isBlank()) {
      throw new RuleDslException("Reason code must not be blank");
    }
  }
}
