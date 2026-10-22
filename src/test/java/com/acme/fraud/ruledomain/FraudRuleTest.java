package com.acme.fraud.ruledomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudRuleTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");

  private static RuleDraft draft(int priority, Instant from, Instant to) {
    return new RuleDraft(
        "High value",
        "desc",
        priority,
        new Condition.Leaf("amount", Operator.GT, "5000"),
        new RuleAction(40, "HIGH_VALUE"),
        from,
        to);
  }

  @Test
  void firstVersionIsOneAndDisabled() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    assertThat(rule.latest().version()).isEqualTo(1);
    assertThat(rule.latest().enabled()).isFalse();
  }

  @Test
  void publishingIncrementsVersionAndKeepsOldVersionUntouched() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    RuleVersion v1 = rule.get(1);
    rule.publish(draft(20, null, null));
    assertThat(rule.latest().version()).isEqualTo(2);
    assertThat(rule.get(1)).isEqualTo(v1);
    assertThat(rule.get(1).priority()).isEqualTo(10);
  }

  @Test
  void activateAndDeactivateOnlyAffectTargetVersion() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    rule.publish(draft(20, null, null));
    rule.activate(1);
    assertThat(rule.get(1).enabled()).isTrue();
    assertThat(rule.get(2).enabled()).isFalse();
    rule.deactivate(1);
    assertThat(rule.versions()).hasSize(2);
    assertThat(rule.get(1).enabled()).isFalse();
  }

  @Test
  void versionsViewIsReadOnly() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    List<RuleVersion> view = rule.versions();
    assertThatThrownBy(() -> view.remove(0)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void unknownVersionIsRejected() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    assertThatThrownBy(() -> rule.get(2)).isInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> rule.activate(0)).isInstanceOf(RuleDslException.class);
  }

  @Test
  void effectiveWindowIsFromInclusiveToExclusive() {
    RuleVersion v = FraudRule.create("r1", draft(10, T0, T1)).latest().activate();
    assertThat(v.isApplicableAt(T0.minusSeconds(1))).isFalse();
    assertThat(v.isApplicableAt(T0)).isTrue();
    assertThat(v.isApplicableAt(T1)).isFalse();
  }

  @Test
  void disabledVersionIsNeverApplicable() {
    RuleVersion v = FraudRule.create("r1", draft(10, null, null)).latest();
    assertThat(v.isApplicableAt(T0)).isFalse();
  }

  @Test
  void invalidWindowAndActionAreRejected() {
    assertThatThrownBy(() -> FraudRule.create("r1", draft(10, T1, T0)))
        .isInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> new RuleAction(-1, "X")).isInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> new RuleAction(1, " ")).isInstanceOf(RuleDslException.class);
  }

  @Test
  void rehydrateRequiresContiguousHistory() {
    FraudRule rule = FraudRule.create("r1", draft(10, null, null));
    rule.publish(draft(20, null, null));
    assertThat(FraudRule.rehydrate("r1", rule.versions()).latest().version()).isEqualTo(2);
    assertThatThrownBy(() -> FraudRule.rehydrate("r1", List.of(rule.get(2))))
        .isInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> FraudRule.rehydrate("other", rule.versions()))
        .isInstanceOf(RuleDslException.class);
  }
}
