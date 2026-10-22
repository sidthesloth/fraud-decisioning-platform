package com.acme.fraud.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.fraud.evaluation.ContextEnricher;
import com.acme.fraud.evaluation.EnrichedAggregates;
import com.acme.fraud.evaluation.EnrichmentUnavailableException;
import com.acme.fraud.evaluation.TransactionFacts;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilientContextEnricherTest {

  private static final TransactionFacts FACTS =
      new TransactionFacts(
          "txn_1",
          "cust_1",
          "acc_1",
          new BigDecimal("10.00"),
          "ZAR",
          "MCC5999",
          "m1",
          "CARD_PRESENT",
          Instant.parse("2026-10-17T18:00:00Z"),
          "ZA",
          "Cape Town",
          "dev_1");

  private static CircuitBreaker breaker() {
    return CircuitBreaker.of(
        "t",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .build());
  }

  private static Bulkhead bulkhead(int max) {
    return Bulkhead.of(
        "t",
        BulkheadConfig.custom().maxConcurrentCalls(max).maxWaitDuration(Duration.ZERO).build());
  }

  @Test
  void passesThroughOnSuccess() {
    EnrichedAggregates expected = EnrichedAggregates.empty();
    ContextEnricher enricher = new ResilientContextEnricher(f -> expected, breaker(), bulkhead(1));
    assertThat(enricher.enrich(FACTS)).isSameAs(expected);
  }

  @Test
  void opensCircuitAfterFailuresAndStopsCallingDelegate() {
    AtomicInteger calls = new AtomicInteger();
    ContextEnricher failing =
        f -> {
          calls.incrementAndGet();
          throw new EnrichmentUnavailableException("db down", new RuntimeException());
        };
    ContextEnricher enricher = new ResilientContextEnricher(failing, breaker(), bulkhead(1));

    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> enricher.enrich(FACTS))
          .isInstanceOf(EnrichmentUnavailableException.class);
    }
    assertThat(calls).hasValue(2);
  }

  @Test
  void rejectsWithEnrichmentUnavailableWhenBulkheadFull() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ContextEnricher slow =
        f -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return EnrichedAggregates.empty();
        };
    ContextEnricher enricher = new ResilientContextEnricher(slow, breaker(), bulkhead(1));

    Thread first = new Thread(() -> enricher.enrich(FACTS));
    first.start();
    entered.await();
    try {
      assertThatThrownBy(() -> enricher.enrich(FACTS))
          .isInstanceOf(EnrichmentUnavailableException.class)
          .hasMessageContaining("bulkhead");
    } finally {
      release.countDown();
      first.join();
    }
  }
}
