package com.acme.fraud.ruledomain;

import java.util.List;

/** Port for persisting rules and their append-only versions. */
public interface RuleCatalog {

  /** Creates rule {@code ruleId} with version 1 (disabled). */
  StoredVersion create(String ruleId, RuleDraft draft, String conditionJson);

  /** Appends the next version (disabled) to an existing rule. */
  StoredVersion addVersion(String ruleId, RuleDraft draft, String conditionJson);

  /** Only the lifecycle flag changes; version content is never rewritten. */
  StoredVersion setEnabled(String ruleId, int version, boolean enabled);

  /** Latest version of every rule, ordered by rule id. */
  List<StoredVersion> latestVersions();

  /** All versions of a rule, oldest first; empty if the rule does not exist. */
  List<StoredVersion> versions(String ruleId);

  /** A persisted version together with the original condition JSON. */
  record StoredVersion(RuleVersion version, String conditionJson) {}

  class RuleAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RuleAlreadyExistsException(String ruleId) {
      super("Rule already exists: " + ruleId);
    }
  }

  class RuleNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RuleNotFoundException(String message) {
      super(message);
    }
  }
}
