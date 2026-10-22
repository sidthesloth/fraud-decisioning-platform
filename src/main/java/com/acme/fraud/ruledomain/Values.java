package com.acme.fraud.ruledomain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/** Numeric coercion shared by the model validation and the evaluator. */
final class Values {

  private Values() {}

  /** Converts a Number or a numeric string to BigDecimal; empty for anything else. */
  static Optional<BigDecimal> toNumber(Object value) {
    if (value instanceof BigDecimal decimal) {
      return Optional.of(decimal);
    }
    if (value instanceof BigInteger integer) {
      return Optional.of(new BigDecimal(integer));
    }
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return Optional.empty();
      }
      return Optional.of(BigDecimal.valueOf(d));
    }
    if (value instanceof Number number) {
      return parse(number.toString());
    }
    if (value instanceof String text) {
      return parse(text.trim());
    }
    return Optional.empty();
  }

  private static Optional<BigDecimal> parse(String text) {
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new BigDecimal(text));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
