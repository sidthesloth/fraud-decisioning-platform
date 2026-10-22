package com.acme.fraud.ruledomain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable condition tree: nested {@code all}/{@code any} combinators over leaf comparisons. */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Leaf {

  /** True when every child is true. Children are non-empty. */
  record All(List<Condition> children) implements Condition {
    public All {
      children = copyNonEmpty(children, "all");
    }
  }

  /** True when at least one child is true. Children are non-empty. */
  record Any(List<Condition> children) implements Condition {
    public Any {
      children = copyNonEmpty(children, "any");
    }
  }

  /**
   * A comparison of a context field against a value. {@code value} is a String, Number, Boolean or
   * a List of those scalars; it is null only for {@link Operator#EXISTS}.
   */
  record Leaf(String field, Operator op, Object value) implements Condition {
    public Leaf {
      if (field == null || field.isBlank()) {
        throw new RuleDslException("Field path must not be blank");
      }
      if (op == null) {
        throw new RuleDslException("Operator must not be null");
      }
      if (op == Operator.EXISTS) {
        if (value != null) {
          throw new RuleDslException("EXISTS does not accept a value");
        }
      } else if (op == Operator.IN || op == Operator.NOT_IN) {
        if (!(value instanceof List<?> list)) {
          throw new RuleDslException(op + " requires an array value");
        }
        List<Object> copy = new ArrayList<>(list.size());
        for (Object element : list) {
          requireScalar(element);
          copy.add(element);
        }
        value = Collections.unmodifiableList(copy);
      } else {
        requireScalar(value);
        if (op.isNumericComparison() && Values.toNumber(value).isEmpty()) {
          throw new RuleDslException(op + " requires a numeric value");
        }
      }
    }
  }

  private static void requireScalar(Object value) {
    if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
      throw new RuleDslException("Value must be a string, number or boolean");
    }
  }

  private static List<Condition> copyNonEmpty(List<Condition> children, String name) {
    if (children == null || children.isEmpty()) {
      throw new RuleDslException("'" + name + "' must contain at least one condition");
    }
    List<Condition> copy = new ArrayList<>(children.size());
    for (Condition child : children) {
      if (child == null) {
        throw new RuleDslException("'" + name + "' must not contain null");
      }
      copy.add(child);
    }
    return Collections.unmodifiableList(copy);
  }
}
