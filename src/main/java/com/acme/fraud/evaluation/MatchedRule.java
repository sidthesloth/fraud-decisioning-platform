package com.acme.fraud.evaluation;

/** Immutable snapshot of a rule version that matched, stored with the decision. */
public record MatchedRule(String ruleId, int version, int contribution, String reasonCode) {}
