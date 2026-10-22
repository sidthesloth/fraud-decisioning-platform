package com.acme.fraud.ruledomain;

import java.time.Instant;

/**
 * One immutable published version of a rule. Content never changes; only the {@code enabled}
 * lifecycle flag can be toggled, which yields a new instance. Corrections are a new version.
 */
public record RuleVersion(
    String ruleId,
    int version,
    String name,
    String description,
    int priority,
    Condition condition,
    RuleAction action,
    boolean enabled,
    Instant effectiveFrom,
    Instant effectiveTo) {

  public RuleVersion {
    if (ruleId == null || ruleId.isBlank()) {
      throw new RuleDslException("Rule id must not be blank");
    }
    if (version < 1) {
      throw new RuleDslException("Version must be >= 1");
    }
    if (name == null || name.isBlank()) {
      throw new RuleDslException("Rule name must not be blank");
    }
    if (condition == null || action == null) {
      throw new RuleDslException("Condition and action are required");
    }
    if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw new RuleDslException("effectiveTo must be after effectiveFrom");
    }
  }

  /** Enabled and inside its effective window ({@code from} inclusive, {@code to} exclusive). */
  public boolean isApplicableAt(Instant instant) {
    if (!enabled) {
      return false;
    }
    if (effectiveFrom != null && instant.isBefore(effectiveFrom)) {
      return false;
    }
    return effectiveTo == null || instant.isBefore(effectiveTo);
  }

  public RuleVersion activate() {
    return withEnabled(true);
  }

  public RuleVersion deactivate() {
    return withEnabled(false);
  }

  private RuleVersion withEnabled(boolean value) {
    return new RuleVersion(
        ruleId,
        version,
        name,
        description,
        priority,
        condition,
        action,
        value,
        effectiveFrom,
        effectiveTo);
  }
}
