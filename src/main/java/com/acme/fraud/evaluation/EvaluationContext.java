package com.acme.fraud.evaluation;

import com.acme.fraud.ruledomain.FieldResolver;
import java.util.Optional;
import java.util.Set;

/**
 * Enriched view of a transaction, readable by the rule DSL through field paths. When {@code
 * enrichmentDegraded} is true every enriched path resolves as unavailable.
 */
public final class EvaluationContext implements FieldResolver {

  static final Set<String> ENRICHED_FIELDS =
      Set.of(
          "txn.lastHourCount",
          "txn.last24hAmount",
          "device.isNew",
          "geo.crossBorder",
          "customer.riskTier",
          "geo.velocity");

  private final TransactionFacts facts;
  private final EnrichedAggregates aggregates;
  private final boolean enrichmentDegraded;

  private EvaluationContext(
      TransactionFacts facts, EnrichedAggregates aggregates, boolean enrichmentDegraded) {
    this.facts = facts;
    this.aggregates = aggregates;
    this.enrichmentDegraded = enrichmentDegraded;
  }

  public static EvaluationContext enriched(TransactionFacts facts, EnrichedAggregates aggregates) {
    return new EvaluationContext(facts, aggregates, false);
  }

  public static EvaluationContext degraded(TransactionFacts facts) {
    return new EvaluationContext(facts, EnrichedAggregates.empty(), true);
  }

  public TransactionFacts facts() {
    return facts;
  }

  public boolean enrichmentDegraded() {
    return enrichmentDegraded;
  }

  public static boolean isEnrichedField(String path) {
    return ENRICHED_FIELDS.contains(path);
  }

  @Override
  public Optional<Object> resolve(String fieldPath) {
    return Optional.ofNullable(
        switch (fieldPath) {
          case "amount" -> facts.amount();
          case "currency" -> facts.currency();
          case "merchantCategory" -> facts.merchantCategory();
          case "merchantId" -> facts.merchantId();
          case "channel" -> facts.channel();
          case "deviceId" -> facts.deviceId();
          case "geo.country" -> facts.country();
          case "geo.city" -> facts.city();
          case "txn.lastHourCount" -> aggregates.lastHourCount();
          case "txn.last24hAmount" -> aggregates.last24hAmount();
          case "device.isNew" -> aggregates.newDevice();
          case "geo.crossBorder" -> aggregates.crossBorder();
          case "customer.riskTier" -> aggregates.riskTier();
          case "geo.velocity" -> aggregates.geoVelocity();
          default -> null;
        });
  }
}
