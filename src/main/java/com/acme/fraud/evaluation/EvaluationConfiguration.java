package com.acme.fraud.evaluation;

import com.acme.fraud.evaluation.internal.JdbcContextEnricher;
import com.acme.fraud.evaluation.internal.ResilientContextEnricher;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {

  @Bean
  ContextEnricher contextEnricher(
      JdbcClient jdbc,
      @Value("${fraud.evaluation.home-country:ZA}") String homeCountry,
      @Value("${fraud.evaluation.enricher.max-concurrent:20}") int maxConcurrent) {
    return new ResilientContextEnricher(
        new JdbcContextEnricher(jdbc, homeCountry),
        CircuitBreaker.ofDefaults("context-enricher"),
        Bulkhead.of(
            "context-enricher", BulkheadConfig.custom().maxConcurrentCalls(maxConcurrent).build()));
  }

  @Bean
  FraudEvaluator fraudEvaluator(
      ContextEnricher enricher,
      Clock clock,
      MeterRegistry registry,
      @Value("${fraud.evaluation.threshold.review:40}") int review,
      @Value("${fraud.evaluation.threshold.decline:80}") int decline) {
    return new FraudEvaluator(
        enricher,
        new RuleEngine(),
        new DeterministicRiskScorer(),
        new Thresholds(review, decline),
        clock,
        Timer.builder("fraud.evaluation.duration").publishPercentileHistogram().register(registry));
  }
}
