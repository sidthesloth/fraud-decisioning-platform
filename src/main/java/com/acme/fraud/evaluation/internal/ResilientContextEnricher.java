package com.acme.fraud.evaluation.internal;

import com.acme.fraud.evaluation.ContextEnricher;
import com.acme.fraud.evaluation.EnrichedAggregates;
import com.acme.fraud.evaluation.EnrichmentUnavailableException;
import com.acme.fraud.evaluation.TransactionFacts;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Decorates an enricher with a bulkhead and circuit breaker. Every failure mode surfaces as {@link
 * EnrichmentUnavailableException} so evaluation degrades instead of blocking.
 */
public final class ResilientContextEnricher implements ContextEnricher {

  private final ContextEnricher delegate;
  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;

  public ResilientContextEnricher(
      ContextEnricher delegate, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
    this.delegate = delegate;
    this.circuitBreaker = circuitBreaker;
    this.bulkhead = bulkhead;
  }

  @Override
  public EnrichedAggregates enrich(TransactionFacts facts) {
    try {
      return Bulkhead.decorateSupplier(
              bulkhead,
              CircuitBreaker.decorateSupplier(circuitBreaker, () -> delegate.enrich(facts)))
          .get();
    } catch (BulkheadFullException e) {
      throw new EnrichmentUnavailableException("Enrichment bulkhead full", e);
    } catch (CallNotPermittedException e) {
      throw new EnrichmentUnavailableException("Enrichment circuit open", e);
    }
  }
}
