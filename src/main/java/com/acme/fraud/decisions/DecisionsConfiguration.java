package com.acme.fraud.decisions;

import com.acme.fraud.decisions.internal.JdbcDecisionStore;
import com.acme.fraud.decisions.internal.MeteredDecisionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class DecisionsConfiguration {

  @Bean
  DecisionStore decisionStore(
      JdbcClient jdbc, TransactionTemplate tx, JsonMapper json, MeterRegistry registry) {
    return new MeteredDecisionStore(new JdbcDecisionStore(jdbc, tx, json), registry);
  }
}
