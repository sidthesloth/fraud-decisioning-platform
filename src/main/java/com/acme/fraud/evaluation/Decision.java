package com.acme.fraud.evaluation;

import java.time.Instant;
import java.util.List;

/** Result of evaluating one transaction. Matched-rule snapshots are stored with the decision. */
public record Decision(
    String transactionId,
    String customerId,
    Outcome outcome,
    int score,
    List<MatchedRule> matchedRules,
    List<SkippedRule> skippedRules,
    boolean enrichmentDegraded,
    String inputHash,
    Instant evaluatedAt,
    String engineVersion) {

  public Decision {
    matchedRules = List.copyOf(matchedRules);
    skippedRules = List.copyOf(skippedRules);
  }

  public boolean flagged() {
    return outcome != Outcome.APPROVE;
  }
}
