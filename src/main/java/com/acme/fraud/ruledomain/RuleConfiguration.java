package com.acme.fraud.ruledomain;

import com.acme.fraud.ruledomain.internal.JdbcRuleCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class RuleConfiguration {

  @Bean
  RuleCatalog ruleCatalog(JdbcClient jdbc, TransactionTemplate tx) {
    return new JdbcRuleCatalog(jdbc, tx, new ConditionParser());
  }
}
