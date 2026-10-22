package com.acme.fraud.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.ruledomain.Condition;
import com.acme.fraud.ruledomain.Operator;
import com.acme.fraud.ruledomain.RuleAction;
import com.acme.fraud.ruledomain.RuleVersion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudEvaluatorTest {

  private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static TransactionFacts facts(String amount, String country) {
    return new TransactionFacts(
        "txn_1",
        "cust_9",
        "acc_4",
        new BigDecimal(amount),
        "ZAR",
        "MCC5999",
        "merch_77",
        "CARD_PRESENT",
        Instant.parse("2026-10-17T17:59:00Z"),
        country,
        "Cape Town",
        "dev_2");
  }

  private static RuleVersion rule(
      String id, int priority, Condition condition, int score, boolean enabled) {
    return new RuleVersion(
        id,
        1,
        id,
        null,
        priority,
        condition,
        new RuleAction(score, id + "_HIT"),
        enabled,
        null,
        null);
  }

  private static Condition amountGt(String v) {
    return new Condition.Leaf("amount", Operator.GT, v);
  }

  private static Condition velocityGt(int v) {
    return new Condition.Leaf("txn.lastHourCount", Operator.GT, v);
  }

  private FraudEvaluator evaluator(ContextEnricher enricher) {
    return new FraudEvaluator(
        enricher, new RuleEngine(), new DeterministicRiskScorer(), Thresholds.DEFAULT, CLOCK);
  }

  private static final ContextEnricher OK =
      f -> new EnrichedAggregates(5, new BigDecimal("100"), false, false, "LOW", null);

  private static final ContextEnricher DOWN =
      f -> {
        throw new EnrichmentUnavailableException("db down", null);
      };

  @Test
  void recordsEvaluationDuration() {
    io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    io.micrometer.core.instrument.Timer timer = registry.timer("fraud.evaluation.duration");
    new FraudEvaluator(
            OK, new RuleEngine(), new DeterministicRiskScorer(), Thresholds.DEFAULT, CLOCK, timer)
        .evaluate(facts("10.00", "ZA"), List.of(), "h");
    assertThat(timer.count()).isEqualTo(1);
  }

  @Test
  void approvesWhenNothingMatches() {
    Decision d =
        evaluator(OK)
            .evaluate(
                facts("10.00", "ZA"), List.of(rule("R1", 1, amountGt("5000"), 90, true)), "h");
    assertThat(d.outcome()).isEqualTo(Outcome.APPROVE);
    assertThat(d.score()).isZero();
    assertThat(d.flagged()).isFalse();
  }

  @Test
  void declinesAndSnapshotsMatchedRulesInPriorityOrder() {
    List<RuleVersion> rules =
        List.of(rule("B", 2, velocityGt(3), 30, true), rule("A", 1, amountGt("5000"), 60, true));
    Decision d = evaluator(OK).evaluate(facts("6000.00", "ZA"), rules, "hash");
    assertThat(d.score()).isEqualTo(90);
    assertThat(d.outcome()).isEqualTo(Outcome.DECLINE);
    assertThat(d.matchedRules()).extracting(MatchedRule::ruleId).containsExactly("A", "B");
    assertThat(d.matchedRules().get(0).version()).isEqualTo(1);
    assertThat(d.inputHash()).isEqualTo("hash");
    assertThat(d.evaluatedAt()).isEqualTo(NOW);
  }

  @Test
  void reviewBetweenThresholdsAndScoreCappedAt100() {
    Decision review =
        evaluator(OK)
            .evaluate(facts("6000", "ZA"), List.of(rule("A", 1, amountGt("5000"), 40, true)), "h");
    assertThat(review.outcome()).isEqualTo(Outcome.REVIEW);
    Decision capped =
        evaluator(OK)
            .evaluate(
                facts("6000", "ZA"),
                List.of(
                    rule("A", 1, amountGt("5000"), 90, true),
                    rule("B", 2, amountGt("1"), 90, true)),
                "h");
    assertThat(capped.score()).isEqualTo(100);
  }

  @Test
  void disabledRulesAreIgnored() {
    Decision d =
        evaluator(OK)
            .evaluate(facts("6000", "ZA"), List.of(rule("A", 1, amountGt("5000"), 90, false)), "h");
    assertThat(d.matchedRules()).isEmpty();
  }

  @Test
  void enrichmentOutageDegradesAndSkipsDependentRulesOnly() {
    List<RuleVersion> rules =
        List.of(
            rule("VEL", 1, velocityGt(3), 90, true), rule("AMT", 2, amountGt("5000"), 50, true));
    Decision d = evaluator(DOWN).evaluate(facts("6000", "ZA"), rules, "h");
    assertThat(d.enrichmentDegraded()).isTrue();
    assertThat(d.matchedRules()).extracting(MatchedRule::ruleId).containsExactly("AMT");
    assertThat(d.skippedRules())
        .containsExactly(new SkippedRule("VEL", 1, SkippedRule.Reason.ENRICHMENT_DEGRADED));
    assertThat(d.outcome()).isEqualTo(Outcome.REVIEW);
  }

  @Test
  void ruleThatThrowsIsSkippedExplicitly() {
    // GT against a non-numeric field value raises a DSL error
    Condition bad = new Condition.Leaf("channel", Operator.GT, 5);
    Decision d =
        evaluator(OK).evaluate(facts("1", "ZA"), List.of(rule("BAD", 1, bad, 50, true)), "h");
    assertThat(d.skippedRules())
        .containsExactly(new SkippedRule("BAD", 1, SkippedRule.Reason.EVALUATION_ERROR));
  }

  @Test
  void thresholdsAreValidated() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Thresholds(50, 50))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
