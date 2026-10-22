package com.acme.fraud.ruledomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

class ConditionParserTest {

  private static final String SPEC_EXAMPLE =
      """
      {
        "all": [
          { "field": "amount", "op": "GT", "value": "5000.00" },
          { "any": [
            { "field": "channel", "op": "EQ", "value": "CARD_NOT_PRESENT" },
            { "field": "geo.country", "op": "NOT_IN", "value": ["ZA", "NA"] }
          ]},
          { "field": "txn.lastHourCount", "op": "GT", "value": 3 }
        ]
      }
      """;

  private final ConditionParser parser = new ConditionParser();

  @Test
  void parsesSpecExample() {
    Condition expected =
        new Condition.All(
            List.of(
                new Condition.Leaf("amount", Operator.GT, "5000.00"),
                new Condition.Any(
                    List.of(
                        new Condition.Leaf("channel", Operator.EQ, "CARD_NOT_PRESENT"),
                        new Condition.Leaf("geo.country", Operator.NOT_IN, List.of("ZA", "NA")))),
                new Condition.Leaf("txn.lastHourCount", Operator.GT, new BigDecimal("3"))));

    assertThat(parser.parse(SPEC_EXAMPLE)).isEqualTo(expected);
  }

  @Test
  void operatorEnumIsClosedAtNineValues() {
    assertThat(Operator.values())
        .containsExactly(
            Operator.EQ,
            Operator.NEQ,
            Operator.GT,
            Operator.GTE,
            Operator.LT,
            Operator.LTE,
            Operator.IN,
            Operator.NOT_IN,
            Operator.EXISTS);
  }

  @ParameterizedTest
  @EnumSource(Operator.class)
  void parsesEveryOperator(Operator op) {
    String value =
        switch (op) {
          case EXISTS -> "";
          case IN, NOT_IN -> ", \"value\": [1, 2]";
          default -> ", \"value\": 7";
        };
    Condition parsed = parser.parse("{\"field\":\"x\",\"op\":\"" + op.name() + "\"" + value + "}");

    assertThat(parsed).isInstanceOf(Condition.Leaf.class);
    assertThat(((Condition.Leaf) parsed).op()).isEqualTo(op);
  }

  @Test
  void parsesAnyAndBooleanValue() {
    assertThat(
            parser.parse(
                "{\"any\":[{\"field\":\"flag\",\"op\":\"EQ\",\"value\":true},"
                    + "{\"field\":\"d\",\"op\":\"EXISTS\"}]}"))
        .isEqualTo(
            new Condition.Any(
                List.of(
                    new Condition.Leaf("flag", Operator.EQ, Boolean.TRUE),
                    new Condition.Leaf("d", Operator.EXISTS, null))));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"field\":\"a\",\"op\":\"BETWEEN\",\"value\":1}",
        "{\"field\":\"a\",\"op\":\"eq\",\"value\":1}",
        "{\"field\":\"a\",\"op\":5,\"value\":1}",
        "{\"field\":\"a\",\"value\":1}",
        "{\"op\":\"EQ\",\"value\":1}",
        "{\"field\":\"a\",\"op\":\"EQ\"}",
        "{\"field\":\"  \",\"op\":\"EQ\",\"value\":1}",
        "{\"field\":3,\"op\":\"EQ\",\"value\":1}",
        "{\"field\":\"a\",\"op\":\"IN\",\"value\":\"x\"}",
        "{\"field\":\"a\",\"op\":\"NOT_IN\",\"value\":[[1]]}",
        "{\"field\":\"a\",\"op\":\"GT\",\"value\":\"abc\"}",
        "{\"field\":\"a\",\"op\":\"GTE\",\"value\":true}",
        "{\"field\":\"a\",\"op\":\"LT\",\"value\":[1]}",
        "{\"field\":\"a\",\"op\":\"EXISTS\",\"value\":1}",
        "{\"field\":\"a\",\"op\":\"EQ\",\"value\":{\"k\":1}}",
        "{\"field\":\"a\",\"op\":\"EQ\",\"value\":null}",
        "{\"field\":\"a\",\"op\":\"EQ\",\"value\":1,\"extra\":1}",
        "{\"all\":[{\"field\":\"a\",\"op\":\"EXISTS\"}],\"field\":\"a\"}",
        "{\"all\":[{\"field\":\"a\",\"op\":\"EXISTS\"}],\"any\":[{\"field\":\"a\",\"op\":\"EXISTS\"}]}",
        "{\"all\":[{\"field\":\"a\",\"op\":\"EXISTS\"}],\"extra\":1}",
        "{}",
        "{\"all\":{}}",
        "{\"all\":[]}",
        "{\"any\":[]}",
        "{\"all\":[1]}",
        "{\"all\":[null]}",
        "[]",
        "[{\"field\":\"a\",\"op\":\"EXISTS\"}]",
        "\"text\"",
        "42",
        "null",
        "",
        "   ",
        "{\"all\":[",
        "{not json}"
      })
  void rejectsInvalidInput(String json) {
    assertThatThrownBy(() -> parser.parse(json)).isExactlyInstanceOf(RuleDslException.class);
  }

  @Test
  void rejectsNullInputs() {
    assertThatThrownBy(() -> parser.parse((String) null))
        .isExactlyInstanceOf(RuleDslException.class);
    assertThatThrownBy(() -> parser.parse((tools.jackson.databind.JsonNode) null))
        .isExactlyInstanceOf(RuleDslException.class);
  }

  @Test
  void wrapsJacksonFailuresWithCause() {
    assertThatThrownBy(() -> parser.parse("{\"all\":["))
        .isExactlyInstanceOf(RuleDslException.class)
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  void errorMessageContainsPath() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    "{\"all\":[{\"field\":\"a\",\"op\":\"EXISTS\"},"
                        + "{\"any\":[{\"field\":\"b\",\"op\":\"NOPE\",\"value\":1}]}]}"))
        .hasMessageContaining("$.all[1].any[0].op");
  }

  @Test
  void acceptsMaxDepthAndRejectsDeeper() {
    assertThat(parser.parse(nested(ConditionParser.MAX_DEPTH))).isNotNull();
    assertThatThrownBy(() -> parser.parse(nested(ConditionParser.MAX_DEPTH + 1)))
        .isExactlyInstanceOf(RuleDslException.class);
  }

  @Test
  void deepNestingNeverOverflowsTheStack() {
    assertThatThrownBy(() -> parser.parse(nested(2000))).isInstanceOf(RuleDslException.class);
  }

  private static String nested(int levels) {
    StringBuilder open = new StringBuilder();
    StringBuilder close = new StringBuilder();
    for (int i = 1; i < levels; i++) {
      open.append("{\"all\":[");
      close.append("]}");
    }
    return open + "{\"field\":\"a\",\"op\":\"EXISTS\"}" + close;
  }
}
