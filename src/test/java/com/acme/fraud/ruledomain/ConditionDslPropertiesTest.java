package com.acme.fraud.ruledomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ConditionDslPropertiesTest {

  private static final List<String> FIELDS =
      List.of("amount", "geo.country", "txn.lastHourCount", "channel", "flag");
  private static final Object MISSING = new Object();
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final ConditionEvaluator evaluator = new ConditionEvaluator();
  private final ConditionParser parser = new ConditionParser();

  // ---- generators ----

  private static Arbitrary<String> field() {
    return Arbitraries.of(FIELDS);
  }

  private static Arbitrary<BigDecimal> number() {
    return Arbitraries.integers().between(-100_000, 100_000).map(BigDecimal::valueOf);
  }

  private static Arbitrary<String> numericString() {
    return Arbitraries.integers().between(-100_000, 100_000).map(i -> i + ".00");
  }

  private static Arbitrary<String> word() {
    return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
  }

  private static Arbitrary<Object> scalar() {
    return Arbitraries.oneOf(
        word().map(s -> (Object) s),
        number().map(n -> (Object) n),
        Arbitraries.of(true, false).map(b -> (Object) b));
  }

  private static Arbitrary<Object> numericValue() {
    return Arbitraries.oneOf(number().map(n -> (Object) n), numericString().map(s -> (Object) s));
  }

  private static Arbitrary<Condition.Leaf> leaf() {
    return Arbitraries.of(Operator.values()).flatMap(ConditionDslPropertiesTest::leafFor);
  }

  private static Arbitrary<Condition.Leaf> leafFor(Operator op) {
    Arbitrary<Object> value =
        switch (op) {
          case EXISTS -> Arbitraries.just(null);
          case IN, NOT_IN -> scalar().list().ofMaxSize(4).map(l -> (Object) l);
          case EQ, NEQ -> scalar();
          default -> numericValue();
        };
    return Combinators.combine(field(), value).as((f, v) -> new Condition.Leaf(f, op, v));
  }

  private static Arbitrary<Condition.Leaf> numericLeaf() {
    return Combinators.combine(
            field(),
            Arbitraries.of(Operator.GT, Operator.GTE, Operator.LT, Operator.LTE),
            numericValue())
        .as(Condition.Leaf::new);
  }

  private static Arbitrary<Condition> tree(Arbitrary<? extends Condition> leaves, int depth) {
    Arbitrary<Condition> leafArb = leaves.map(c -> (Condition) c);
    if (depth == 0) {
      return leafArb;
    }
    Arbitrary<List<Condition>> children =
        Arbitraries.lazy(() -> tree(leaves, depth - 1)).list().ofMinSize(1).ofMaxSize(3);
    return Arbitraries.oneOf(
        leafArb,
        children.map(l -> (Condition) new Condition.All(l)),
        children.map(l -> (Condition) new Condition.Any(l)));
  }

  @Provide
  Arbitrary<Condition> conditions() {
    return tree(leaf(), 3);
  }

  @Provide
  Arbitrary<Condition> numericConditions() {
    return tree(numericLeaf(), 3);
  }

  private static Arbitrary<Object> anyFieldValue() {
    return Arbitraries.oneOf(
        scalar(),
        numericString().map(s -> (Object) s),
        Arbitraries.just(MISSING),
        Arbitraries.just(MISSING));
  }

  private static FieldResolver toContext(List<Object> values) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < FIELDS.size(); i++) {
      if (values.get(i) != MISSING) {
        map.put(FIELDS.get(i), values.get(i));
      }
    }
    return path -> Optional.ofNullable(map.get(path));
  }

  @Provide
  Arbitrary<FieldResolver> contexts() {
    return anyFieldValue().list().ofSize(FIELDS.size()).map(ConditionDslPropertiesTest::toContext);
  }

  @Provide
  Arbitrary<FieldResolver> numericContexts() {
    Arbitrary<Object> numeric =
        Arbitraries.oneOf(numericValue(), Arbitraries.just(MISSING), Arbitraries.just(MISSING));
    return numeric.list().ofSize(FIELDS.size()).map(ConditionDslPropertiesTest::toContext);
  }

  // ---- serialization helpers ----

  private static String json(Object value) {
    if (value instanceof BigDecimal n) {
      return n.toPlainString();
    }
    return MAPPER.writeValueAsString(value);
  }

  private static String toJson(Condition condition) {
    return switch (condition) {
      case Condition.All all -> "{\"all\":" + children(all.children()) + "}";
      case Condition.Any any -> "{\"any\":" + children(any.children()) + "}";
      case Condition.Leaf leaf -> {
        StringBuilder sb = new StringBuilder("{\"field\":").append(json(leaf.field()));
        sb.append(",\"op\":").append(json(leaf.op().name()));
        if (leaf.op() == Operator.EXISTS) {
          yield sb.append('}').toString();
        }
        if (leaf.value() instanceof List<?> list) {
          List<String> items = new ArrayList<>();
          list.forEach(item -> items.add(json(item)));
          sb.append(",\"value\":[").append(String.join(",", items)).append("]");
        } else {
          sb.append(",\"value\":").append(json(leaf.value()));
        }
        yield sb.append('}').toString();
      }
    };
  }

  private static String children(List<Condition> children) {
    List<String> parts = new ArrayList<>();
    children.forEach(c -> parts.add(toJson(c)));
    return "[" + String.join(",", parts) + "]";
  }

  // ---- properties ----

  @Property(seed = "42")
  void evaluateReturnsBooleanOrRuleDslException(
      @ForAll("conditions") Condition condition, @ForAll("contexts") FieldResolver context) {
    try {
      boolean result = evaluator.evaluate(condition, context);
      assertThat(result).isIn(true, false);
    } catch (RuleDslException expected) {
      assertThat(expected).hasNoCause();
    }
  }

  @Property(seed = "43")
  void numericOnlyConditionsAlwaysReturnNormally(
      @ForAll("numericConditions") Condition condition,
      @ForAll("numericContexts") FieldResolver context) {
    boolean result = evaluator.evaluate(condition, context);
    assertThat(result).isIn(true, false);
  }

  @Property(seed = "44")
  void evaluateIsDeterministic(
      @ForAll("conditions") Condition condition, @ForAll("contexts") FieldResolver context) {
    assertThat(outcome(condition, context)).isEqualTo(outcome(condition, context));
  }

  private String outcome(Condition condition, FieldResolver context) {
    try {
      return String.valueOf(evaluator.evaluate(condition, context));
    } catch (RuleDslException e) {
      return "error:" + e.getMessage();
    }
  }

  @Property(seed = "45")
  void serializeThenParseRoundTrips(@ForAll("conditions") Condition condition) {
    assertThat(parser.parse(toJson(condition))).isEqualTo(condition);
  }

  @Property(seed = "46")
  void anyStrictPrefixOfValidJsonIsRejected(
      @ForAll("conditions") Condition condition, @ForAll int cut) {
    String json = toJson(condition);
    String prefix = json.substring(0, Math.floorMod(cut, json.length()));
    assertThatThrownBy(() -> parser.parse(prefix)).isExactlyInstanceOf(RuleDslException.class);
  }

  @Property(seed = "47")
  void mutatedConditionsAreRejected(@ForAll("invalidJson") String json) {
    assertThatThrownBy(() -> parser.parse(json)).isExactlyInstanceOf(RuleDslException.class);
  }

  @Property(seed = "48")
  void randomGarbageIsRejected(@ForAll String garbage) {
    String text = "x" + garbage;
    assertThatThrownBy(() -> parser.parse(text)).isExactlyInstanceOf(RuleDslException.class);
  }

  @Provide
  Arbitrary<String> invalidJson() {
    Arbitrary<String> unknownOp =
        word()
            .filter(w -> Arrays.stream(Operator.values()).noneMatch(o -> o.name().equals(w)))
            .map(w -> "{\"field\":\"a\",\"op\":\"" + w + "\",\"value\":1}");
    Arbitrary<String> emptyCombinator = Arbitraries.of("all", "any").map(k -> "{\"" + k + "\":[]}");
    Arbitrary<String> nonNumeric =
        Combinators.combine(Arbitraries.of("GT", "GTE", "LT", "LTE"), word())
            .as((op, w) -> "{\"field\":\"a\",\"op\":\"" + op + "\",\"value\":\"" + w + "\"}");
    Arbitrary<String> scalarForIn =
        Combinators.combine(Arbitraries.of("IN", "NOT_IN"), scalar())
            .as((op, v) -> "{\"field\":\"a\",\"op\":\"" + op + "\",\"value\":" + json(v) + "}");
    Arbitrary<String> existsWithValue =
        scalar().map(v -> "{\"field\":\"a\",\"op\":\"EXISTS\",\"value\":" + json(v) + "}");
    Arbitrary<String> wrongTypes =
        Arbitraries.of(
            "{\"field\":\"a\",\"op\":\"EQ\",\"value\":null}",
            "{\"field\":\"a\",\"op\":\"EQ\",\"value\":{}}",
            "{\"field\":\"a\",\"op\":\"EQ\",\"value\":[1]}",
            "{\"field\":1,\"op\":\"EQ\",\"value\":1}",
            "{\"all\":{}}",
            "{\"all\":[1]}",
            "[]");
    return Arbitraries.oneOf(
        unknownOp, emptyCombinator, nonNumeric, scalarForIn, existsWithValue, wrongTypes);
  }
}
