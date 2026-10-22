package com.acme.fraud.alert;

import com.acme.fraud.evaluation.Outcome;

public enum Severity {
  LOW,
  MEDIUM,
  HIGH,
  CRITICAL;

  /** TODO(ADAPTER): severity bands are placeholders pending business confirmation. */
  public static Severity of(Outcome outcome, int score) {
    if (outcome == Outcome.DECLINE) {
      return score >= 95 ? CRITICAL : HIGH;
    }
    return score >= 60 ? MEDIUM : LOW;
  }
}
