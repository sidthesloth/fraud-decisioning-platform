package com.acme.fraud.ruledomain;

/** Closed set of comparison operators supported by the condition DSL. */
public enum Operator {
  EQ,
  NEQ,
  GT,
  GTE,
  LT,
  LTE,
  IN,
  NOT_IN,
  EXISTS;

  /** Case-sensitive lookup of the JSON token; unknown tokens raise {@link RuleDslException}. */
  public static Operator fromJson(String token) {
    if (token != null) {
      for (Operator operator : values()) {
        if (operator.name().equals(token)) {
          return operator;
        }
      }
    }
    throw new RuleDslException("Unknown operator");
  }

  boolean isNumericComparison() {
    return this == GT || this == GTE || this == LT || this == LTE;
  }
}
