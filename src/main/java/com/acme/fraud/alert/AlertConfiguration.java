package com.acme.fraud.alert;

import com.acme.fraud.alert.internal.JdbcAlertStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class AlertConfiguration {

  @Bean
  AlertStore alertStore(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json, Clock clock) {
    return new JdbcAlertStore(jdbc, tx, json, clock);
  }
}
