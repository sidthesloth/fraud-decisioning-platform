package com.acme.fraud.evaluation;

/** Port that derives historical aggregates for a transaction. */
public interface ContextEnricher {

  /**
   * @throws EnrichmentUnavailableException when aggregates cannot be produced (outage, open
   *     circuit, bulkhead full). Callers degrade rather than fail.
   */
  EnrichedAggregates enrich(TransactionFacts facts);
}
