package com.acme.fraud.ruledomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConditionEvaluatorTest {

  private static final String SPEC_EXAMPLE =
      "{\"all\":[{\"field\":\"amount\",\"op\":\"GT\",\"value\":\"5000.00\"},"
          + "{\"any\":[{\"field\":\"channel\",\"op\":\"EQ\",\"value\":\"CARD_NOT_PRESENT\"},"
          + "{\"field\":\"geo.country\",\"op\":\"NOT_IN\",\"value\":[\"ZA\",\"NA\"]}]},"
          + "{\"field\":\"txn.lastHourCount\",\"op\":\"GT\",\"value\":3}]}";

  private final ConditionEvaluator evaluator = new ConditionEvaluator();
  private final ConditionParser parser = new ConditionParser();

  private static FieldResolver ctx(Object... keyValues) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put((String) keyValues[i], keyValues[i + 1]);
    }
    return path -> Optional.ofNullable(map.get(path));
  }

  private boolean eval(String field, Operator op, Object value, FieldResolver context) {
    return evaluator.evaluate(new Condition.Leaf(field, op, value), context);
  }

  @Test
  void numericStringsCompareNumerically() {
    assertThat(eval("amount", Operator.GT, "5000.00", ctx("amount", "1250.00"))).isFalse();
    assertThat(eval("amount", Operator.LT, "5000", ctx("amount", "900"))).isTrue();
    assertThat(eval("amount", Operator.GT, "5000.00", ctx("amount", "10000.00"))).isTrue();
  }

  @Test
  void numericOperators() {
    FieldResolver c = ctx("n", 5);
    assertThat(eval("n", Operator.GT, 4, c)).isTrue();
    assertThat(eval("n", Operator.GT, 5, c)).isFalse();
    assertThat(eval("n", Operator.GTE, 5, c)).isTrue();
    assertThat(eval("n", Operator.GTE, 6, c)).isFalse();
    assertThat(eval("n", Operator.LT, 6, c)).isTrue();
    assertThat(eval("n", Operator.LT, 5, c)).isFalse();
    assertThat(eval("n", Operator.LTE, 5, c)).isTrue();
    assertThat(eval("n", Operator.LTE, 4, c)).isFalse();
  }

  @Test
  void equalityIsScaleInsensitive() {
    assertThat(eval("n", Operator.EQ, 10, ctx("n", "10.0"))).isTrue();
    assertThat(eval("n", Operator.EQ, new java.math.BigDecimal("1.00"), ctx("n", 1.0))).isTrue();
    assertThat(eval("n", Operator.NEQ, 10, ctx("n", "10.0"))).isFalse();
    assertThat(eval("n", Operator.NEQ, 11, ctx("n", "10.0"))).isTrue();
  }

  @Test
  void stringAndBooleanEquality() {
    assertThat(eval("c", Operator.EQ, "A", ctx("c", "A"))).isTrue();
    assertThat(eval("c", Operator.EQ, "A", ctx("c", "a"))).isFalse();
    assertThat(eval("c", Operator.NEQ, "A", ctx("c", "B"))).isTrue();
    assertThat(eval("f", Operator.EQ, true, ctx("f", true))).isTrue();
    assertThat(eval("f", Operator.EQ, true, ctx("f", false))).isFalse();
    assertThat(eval("f", Operator.EQ, "true", ctx("f", true))).isFalse();
  }

  @Test
  void inAndNotIn() {
    List<Object> countries = List.of("ZA", "NA");
    assertThat(eval("g", Operator.IN, countries, ctx("g", "ZA"))).isTrue();
    assertThat(eval("g", Operator.IN, countries, ctx("g", "US"))).isFalse();
    assertThat(eval("g", Operator.NOT_IN, countries, ctx("g", "ZA"))).isFalse();
    assertThat(eval("g", Operator.NOT_IN, countries, ctx("g", "US"))).isTrue();
    assertThat(eval("n", Operator.IN, List.of(1, 2), ctx("n", "2.0"))).isTrue();
    assertThat(eval("n", Operator.NOT_IN, List.of(1, 2), ctx("n", 3))).isTrue();
  }

  @Test
  void exists() {
    assertThat(eval("a", Operator.EXISTS, null, ctx("a", "x"))).isTrue();
    assertThat(eval("a", Operator.EXISTS, null, ctx())).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Operator.class)
  void missingFieldIsFalseForEveryOperatorExceptExists(Operator op) {
    Object value =
        switch (op) {
          case EXISTS -> null;
          case IN, NOT_IN -> List.of("x");
          default -> 1;
        };
    assertThat(eval("missing", op, value, ctx())).isFalse();
  }

  @Test
  void typeMismatchThrows() {
    assertThatThrownBy(() -> eval("n", Operator.GT, 1, ctx("n", "abc")))
        .isExactlyInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> eval("n", Operator.LTE, 1, ctx("n", true)))
        .isExactlyInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> eval("n", Operator.GT, 1, ctx("n", List.of(1))))
        .isExactlyInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> eval("n", Operator.GT, 1, ctx("n", Double.NaN)))
        .isExactlyInstanceOf(RuleDslException.class);
  }

  @Test
  void nullArgumentsThrowRuleDslException() {
    Condition leaf = new Condition.Leaf("a", Operator.EXISTS, null);
    assertThatThrownBy(() -> evaluator.evaluate(null, ctx()))
        .isExactlyInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> evaluator.evaluate(leaf, null))
        .isExactlyInstanceOf(RuleDslException.class);
  }

  @Test
  void specExampleFraudulentAndBenign() {
    Condition condition = parser.parse(SPEC_EXAMPLE);

    assertThat(
            evaluator.evaluate(
                condition,
                ctx(
                    "amount", "7500.00",
                    "channel", "CARD_PRESENT",
                    "geo.country", "US",
                    "txn.lastHourCount", 5)))
        .isTrue();
    assertThat(
            evaluator.evaluate(
                condition,
                ctx(
                    "amount", "1250.00",
                    "channel", "CARD_PRESENT",
                    "geo.country", "ZA",
                    "txn.lastHourCount", 1)))
        .isFalse();
    assertThat(
            evaluator.evaluate(
                condition,
                ctx(
                    "amount", "9000.00",
                    "channel", "CARD_NOT_PRESENT",
                    "geo.country", "ZA",
                    "txn.lastHourCount", 4)))
        .isTrue();
    assertThat(
            evaluator.evaluate(
                condition,
                ctx(
                    "amount", "9000.00",
                    "channel", "CARD_PRESENT",
                    "geo.country", "ZA",
                    "txn.lastHourCount", 4)))
        .isFalse();
  }
}
