package com.acme.fraud.ruledomain;

import java.time.Instant;

/** Content proposed for a new rule version, before a version number is assigned. */
public record RuleDraft(
    String name,
    String description,
    int priority,
    Condition condition,
    RuleAction action,
    Instant effectiveFrom,
    Instant effectiveTo) {}
