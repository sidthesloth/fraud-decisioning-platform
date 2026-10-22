package com.acme.fraud.api;

import com.acme.fraud.decisions.DecisionStore.StoredDecision;
import com.acme.fraud.evaluation.Decision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full decision including the immutable matched-rule snapshots. */
record DecisionResponse(
    UUID decisionId,
    String transactionId,
    String customerId,
    String outcome,
    int score,
    boolean enrichmentDegraded,
    String inputHash,
    String engineVersion,
    Instant evaluatedAt,
    List<MatchedRuleResponse> matchedRules,
    List<SkippedRuleResponse> skippedRules) {

  record MatchedRuleResponse(String ruleId, int version, int contribution, String reasonCode) {}

  record SkippedRuleResponse(String ruleId, int version, String reason) {}

  static DecisionResponse from(StoredDecision stored) {
    Decision d = stored.decision();
    return new DecisionResponse(
        stored.decisionId(),
        d.transactionId(),
        d.customerId(),
        d.outcome().name(),
        d.score(),
        d.enrichmentDegraded(),
        d.inputHash(),
        d.engineVersion(),
        d.evaluatedAt(),
        d.matchedRules().stream()
            .map(
                m ->
                    new MatchedRuleResponse(
                        m.ruleId(), m.version(), m.contribution(), m.reasonCode()))
            .toList(),
        d.skippedRules().stream()
            .map(s -> new SkippedRuleResponse(s.ruleId(), s.version(), s.reason().name()))
            .toList());
  }
}
