package com.acme.fraud.ingestion;

import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.evaluation.FraudEvaluator;
import com.acme.fraud.ingestion.internal.JdbcDeadLetterReplayer;
import com.acme.fraud.ingestion.internal.JdbcDeadLetterSink;
import com.acme.fraud.ingestion.internal.JdbcIngestionLog;
import com.acme.fraud.ingestion.internal.JdbcRuleSource;
import com.acme.fraud.ingestion.internal.MeteredDeadLetterSink;
import com.acme.fraud.ingestion.internal.TransactionEventListener;
import com.acme.fraud.ruledomain.ConditionParser;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class IngestionConfiguration {

  @Bean
  IngestionLog ingestionLog(JdbcClient jdbc) {
    return new JdbcIngestionLog(jdbc);
  }

  @Bean
  RuleSource ruleSource(JdbcClient jdbc) {
    return new JdbcRuleSource(jdbc, new ConditionParser());
  }

  @Bean
  DeadLetterSink deadLetterSink(JdbcClient jdbc, JsonMapper json, MeterRegistry registry) {
    return new MeteredDeadLetterSink(new JdbcDeadLetterSink(jdbc, json), registry);
  }

  @Bean
  DeadLetterReplayer deadLetterReplayer(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
    return new JdbcDeadLetterReplayer(jdbc, tx, json);
  }

  @Bean
  TransactionEventProcessor transactionEventProcessor(
      JsonMapper json,
      IngestionLog log,
      RuleSource rules,
      FraudEvaluator evaluator,
      DecisionStore decisions,
      AlertStore alerts,
      TransactionTemplate tx) {
    return new TransactionEventProcessor(
        new CloudEventParser(json), log, rules, evaluator, decisions, alerts, tx);
  }

  @Bean
  ResilientEventHandler resilientEventHandler(
      TransactionEventProcessor processor,
      DeadLetterSink dlq,
      @Value("${fraud.ingestion.max-attempts:3}") int maxAttempts,
      @Value("${fraud.ingestion.initial-backoff:500ms}") Duration backoff) {
    return new ResilientEventHandler(processor, dlq, maxAttempts, backoff, d -> Thread.sleep(d));
  }

  @Bean
  TransactionEventListener transactionEventListener(ResilientEventHandler handler) {
    return new TransactionEventListener(handler);
  }
}
