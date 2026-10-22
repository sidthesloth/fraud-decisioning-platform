package com.acme.fraud.ruledomain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregate root for a rule and its append-only version history. Versions are monotonic from 1;
 * published versions are never modified or removed. New versions start disabled so that enabling is
 * an explicit step.
 */
public final class FraudRule {

  private final String ruleId;
  private final List<RuleVersion> versions;

  private FraudRule(String ruleId, List<RuleVersion> versions) {
    this.ruleId = ruleId;
    this.versions = versions;
  }

  public static FraudRule create(String ruleId, RuleDraft draft) {
    FraudRule rule = new FraudRule(ruleId, new ArrayList<>());
    rule.publish(draft);
    return rule;
  }

  /** Rebuilds the aggregate from persisted versions, verifying history integrity. */
  public static FraudRule rehydrate(String ruleId, List<RuleVersion> history) {
    if (history == null || history.isEmpty()) {
      throw new RuleDslException("A rule needs at least one version");
    }
    List<RuleVersion> copy = new ArrayList<>(history);
    for (int i = 0; i < copy.size(); i++) {
      RuleVersion v = copy.get(i);
      if (!v.ruleId().equals(ruleId) || v.version() != i + 1) {
        throw new RuleDslException("Version history is not contiguous for rule " + ruleId);
      }
    }
    return new FraudRule(ruleId, copy);
  }

  public RuleVersion publish(RuleDraft draft) {
    if (draft == null) {
      throw new RuleDslException("Draft is required");
    }
    RuleVersion next =
        new RuleVersion(
            ruleId,
            versions.size() + 1,
            draft.name(),
            draft.description(),
            draft.priority(),
            draft.condition(),
            draft.action(),
            false,
            draft.effectiveFrom(),
            draft.effectiveTo());
    versions.add(next);
    return next;
  }

  public RuleVersion activate(int version) {
    return replace(version, get(version).activate());
  }

  public RuleVersion deactivate(int version) {
    return replace(version, get(version).deactivate());
  }

  public RuleVersion get(int version) {
    if (version < 1 || version > versions.size()) {
      throw new RuleDslException("Unknown version " + version + " of rule " + ruleId);
    }
    return versions.get(version - 1);
  }

  public RuleVersion latest() {
    return versions.getLast();
  }

  public String ruleId() {
    return ruleId;
  }

  public List<RuleVersion> versions() {
    return Collections.unmodifiableList(versions);
  }

  private RuleVersion replace(int version, RuleVersion updated) {
    versions.set(version - 1, updated);
    return updated;
  }
}
