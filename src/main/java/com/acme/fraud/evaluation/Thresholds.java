package com.acme.fraud.evaluation;

/**
 * Score cut-offs: score {@code >= decline} is DECLINE, {@code >= review} is REVIEW, else APPROVE.
 *
 * <p>TODO(ADAPTER): business-approved threshold values are unconfirmed; defaults are placeholders.
 */
public record Thresholds(int review, int decline) {

  public static final Thresholds DEFAULT = new Thresholds(40, 80);

  public Thresholds {
    if (review < 0 || decline <= review || decline > 100) {
      throw new IllegalArgumentException("require 0 <= review < decline <= 100");
    }
  }

  public Outcome outcomeFor(int score) {
    if (score >= decline) {
      return Outcome.DECLINE;
    }
    return score >= review ? Outcome.REVIEW : Outcome.APPROVE;
  }
}
