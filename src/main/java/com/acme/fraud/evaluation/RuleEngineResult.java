package com.acme.fraud.evaluation;

import java.util.List;

public record RuleEngineResult(List<MatchedRule> matched, List<SkippedRule> skipped) {

  public RuleEngineResult {
    matched = List.copyOf(matched);
    skipped = List.copyOf(skipped);
  }

  public int totalContribution() {
    return matched.stream().mapToInt(MatchedRule::contribution).sum();
  }
}
