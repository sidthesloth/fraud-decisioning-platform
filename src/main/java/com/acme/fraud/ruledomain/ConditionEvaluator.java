package com.acme.fraud.ruledomain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic evaluator for {@link Condition} trees.
 *
 * <p>Semantics: {@code all}/{@code any} short-circuit in declaration order, so a type mismatch in a
 * branch that is never reached does not throw. A field the context cannot resolve makes every
 * operator except EXISTS evaluate to false (including NEQ and NOT_IN). GT/GTE/LT/LTE require both
 * sides to be numeric (a Number or a numeric string) and otherwise throw {@link RuleDslException}.
 * EQ/NEQ/IN/NOT_IN compare numerically when both sides are numeric, else by string/boolean
 * equality.
 */
public final class ConditionEvaluator {

  public boolean evaluate(Condition condition, FieldResolver context) {
    if (condition == null || context == null) {
      throw new RuleDslException("Condition and context are required");
    }
    return switch (condition) {
      case Condition.All all -> {
        for (Condition child : all.children()) {
          if (!evaluate(child, context)) {
            yield false;
          }
        }
        yield true;
      }
      case Condition.Any any -> {
        for (Condition child : any.children()) {
          if (evaluate(child, context)) {
            yield true;
          }
        }
        yield false;
      }
      case Condition.Leaf leaf -> evaluateLeaf(leaf, context);
    };
  }

  private boolean evaluateLeaf(Condition.Leaf leaf, FieldResolver context) {
    Optional<Object> resolved = context.resolve(leaf.field());
    if (resolved == null) {
      throw new RuleDslException("Context returned null instead of Optional");
    }
    if (leaf.op() == Operator.EXISTS) {
      return resolved.isPresent();
    }
    if (resolved.isEmpty()) {
      return false;
    }
    Object actual = resolved.get();
    return switch (leaf.op()) {
      case EQ -> valuesEqual(actual, leaf.value());
      case NEQ -> !valuesEqual(actual, leaf.value());
      case IN -> contains(leaf.value(), actual);
      case NOT_IN -> !contains(leaf.value(), actual);
      case GT -> compare(actual, leaf.value()) > 0;
      case GTE -> compare(actual, leaf.value()) >= 0;
      case LT -> compare(actual, leaf.value()) < 0;
      case LTE -> compare(actual, leaf.value()) <= 0;
      case EXISTS -> true;
    };
  }

  private static int compare(Object actual, Object expected) {
    Optional<BigDecimal> left = Values.toNumber(actual);
    Optional<BigDecimal> right = Values.toNumber(expected);
    if (left.isEmpty() || right.isEmpty()) {
      throw new RuleDslException("Numeric comparison requires numeric operands");
    }
    return left.get().compareTo(right.get());
  }

  private static boolean contains(Object collection, Object actual) {
    if (!(collection instanceof List<?> list)) {
      throw new RuleDslException("IN/NOT_IN requires a list value");
    }
    for (Object candidate : list) {
      if (valuesEqual(actual, candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean valuesEqual(Object actual, Object expected) {
    if (actual instanceof Boolean || expected instanceof Boolean) {
      return actual.equals(expected);
    }
    Optional<BigDecimal> left = Values.toNumber(actual);
    Optional<BigDecimal> right = Values.toNumber(expected);
    if (left.isPresent() && right.isPresent()) {
      return left.get().compareTo(right.get()) == 0;
    }
    return String.valueOf(actual).equals(String.valueOf(expected));
  }
}
