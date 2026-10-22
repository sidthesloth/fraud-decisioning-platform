package com.acme.fraud.evaluation;

import com.acme.fraud.ruledomain.Condition;
import com.acme.fraud.ruledomain.ConditionEvaluator;
import com.acme.fraud.ruledomain.RuleDslException;
import com.acme.fraud.ruledomain.RuleVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates applicable rule versions in priority order (lower number first, then rule id for a
 * stable order). Rules that depend on enriched fields are skipped when enrichment is degraded.
 */
public final class RuleEngine {

  private static final Comparator<RuleVersion> ORDER =
      Comparator.comparingInt(RuleVersion::priority)
          .thenComparing(RuleVersion::ruleId)
          .thenComparingInt(RuleVersion::version);

  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  public RuleEngineResult evaluate(
      EvaluationContext context, List<RuleVersion> candidates, Instant at) {
    List<RuleVersion> ordered = new ArrayList<>(candidates);
    ordered.sort(ORDER);
    List<MatchedRule> matched = new ArrayList<>();
    List<SkippedRule> skipped = new ArrayList<>();
    for (RuleVersion rule : ordered) {
      if (!rule.isApplicableAt(at)) {
        continue;
      }
      if (context.enrichmentDegraded() && dependsOnEnrichment(rule.condition())) {
        skipped.add(
            new SkippedRule(rule.ruleId(), rule.version(), SkippedRule.Reason.ENRICHMENT_DEGRADED));
        continue;
      }
      try {
        if (evaluator.evaluate(rule.condition(), context)) {
          matched.add(
              new MatchedRule(
                  rule.ruleId(),
                  rule.version(),
                  rule.action().scoreContribution(),
                  rule.action().reasonCode()));
        }
      } catch (RuleDslException e) {
        skipped.add(
            new SkippedRule(rule.ruleId(), rule.version(), SkippedRule.Reason.EVALUATION_ERROR));
      }
    }
    return new RuleEngineResult(matched, skipped);
  }

  private static boolean dependsOnEnrichment(Condition condition) {
    Set<String> fields = new HashSet<>();
    collectFields(condition, fields);
    return fields.stream().anyMatch(EvaluationContext::isEnrichedField);
  }

  private static void collectFields(Condition condition, Set<String> into) {
    switch (condition) {
      case Condition.All all -> all.children().forEach(c -> collectFields(c, into));
      case Condition.Any any -> any.children().forEach(c -> collectFields(c, into));
      case Condition.Leaf leaf -> into.add(leaf.field());
    }
  }
}
