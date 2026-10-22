package com.acme.fraud.evaluation;

import java.math.BigDecimal;

/**
 * Derived aggregates supplied by a {@link ContextEnricher}. Any component may be null when the
 * enricher cannot determine it; the matching field then resolves as unavailable.
 *
 * <p>TODO(ADAPTER): the source of {@code riskTier} and the definition of {@code geoVelocity} are
 * unconfirmed.
 */
public record EnrichedAggregates(
    Integer lastHourCount,
    BigDecimal last24hAmount,
    Boolean newDevice,
    Boolean crossBorder,
    String riskTier,
    BigDecimal geoVelocity) {

  public static EnrichedAggregates empty() {
    return new EnrichedAggregates(null, null, null, null, null, null);
  }
}
