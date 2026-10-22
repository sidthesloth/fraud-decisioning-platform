package com.acme.fraud.ruledomain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConditionTruthTableTest {

  private final ConditionEvaluator evaluator = new ConditionEvaluator();

  private static Condition flag(String name) {
    return new Condition.Leaf(name, Operator.EQ, Boolean.TRUE);
  }

  private static FieldResolver bits(boolean... values) {
    Map<String, Object> map = new java.util.HashMap<>();
    String[] names = {"a", "b", "c"};
    for (int i = 0; i < values.length; i++) {
      map.put(names[i], values[i]);
    }
    return path -> Optional.ofNullable(map.get(path));
  }

  private static boolean[] row(int index, int width) {
    boolean[] row = new boolean[width];
    for (int i = 0; i < width; i++) {
      row[i] = (index & (1 << i)) != 0;
    }
    return row;
  }

  @Test
  void twoLeaves() {
    Condition all = new Condition.All(List.of(flag("a"), flag("b")));
    Condition any = new Condition.Any(List.of(flag("a"), flag("b")));
    for (int i = 0; i < 4; i++) {
      boolean[] r = row(i, 2);
      assertThat(evaluator.evaluate(all, bits(r))).isEqualTo(r[0] && r[1]);
      assertThat(evaluator.evaluate(any, bits(r))).isEqualTo(r[0] || r[1]);
    }
  }

  @Test
  void threeLeavesNested() {
    Condition flat = new Condition.All(List.of(flag("a"), flag("b"), flag("c")));
    Condition flatAny = new Condition.Any(List.of(flag("a"), flag("b"), flag("c")));
    Condition allAny =
        new Condition.All(List.of(flag("a"), new Condition.Any(List.of(flag("b"), flag("c")))));
    Condition anyAll =
        new Condition.Any(List.of(flag("a"), new Condition.All(List.of(flag("b"), flag("c")))));
    for (int i = 0; i < 8; i++) {
      boolean[] r = row(i, 3);
      FieldResolver c = bits(r);
      assertThat(evaluator.evaluate(flat, c)).isEqualTo(r[0] && r[1] && r[2]);
      assertThat(evaluator.evaluate(flatAny, c)).isEqualTo(r[0] || r[1] || r[2]);
      assertThat(evaluator.evaluate(allAny, c)).isEqualTo(r[0] && (r[1] || r[2]));
      assertThat(evaluator.evaluate(anyAll, c)).isEqualTo(r[0] || (r[1] && r[2]));
    }
  }

  @Test
  void shortCircuitSkipsMismatchInLaterBranch() {
    Condition mismatch = new Condition.Leaf("text", Operator.GT, 1);
    FieldResolver c = path -> Optional.of(path.equals("text") ? "abc" : Boolean.TRUE);

    Condition anyShort = new Condition.Any(List.of(flag("a"), mismatch));
    assertThat(evaluator.evaluate(anyShort, c)).isTrue();

    FieldResolver falseA = path -> Optional.of(path.equals("text") ? "abc" : Boolean.FALSE);
    Condition allShort = new Condition.All(List.of(flag("a"), mismatch));
    assertThat(evaluator.evaluate(allShort, falseA)).isFalse();

    Condition reached = new Condition.All(List.of(flag("a"), mismatch));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluator.evaluate(reached, c))
        .isExactlyInstanceOf(RuleDslException.class);
  }
}
