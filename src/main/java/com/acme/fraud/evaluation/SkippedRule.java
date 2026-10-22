package com.acme.fraud.evaluation;

/** A rule that could not be evaluated, with an explicit reason (never silently dropped). */
public record SkippedRule(String ruleId, int version, Reason reason) {

  public enum Reason {
    ENRICHMENT_DEGRADED,
    EVALUATION_ERROR
  }
}
