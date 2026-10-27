package com.acme.fraud.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.ruledomain.ConditionParser;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JdbcRuleSourceTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static JdbcClient jdbc;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM fraud_rule_versions").update();
    jdbc.sql("DELETE FROM fraud_rules").update();
  }

  @Test
  void ruleSourceLoadsOnlyEnabledVersionsWithParsedCondition() {
    jdbc.sql("INSERT INTO fraud_rules (rule_id) VALUES ('R1')").update();
    for (int v = 1; v <= 2; v++) {
      jdbc.sql(
              "INSERT INTO fraud_rule_versions (rule_id, version, name, priority, condition,"
                  + " score, reason_code, enabled) VALUES ('R1', :v, 'R', 1,"
                  + " '{\"field\":\"amount\",\"op\":\"GT\",\"value\":\"5000\"}'::jsonb, 50, 'HV',"
                  + " :en)")
          .param("v", v)
          .param("en", v == 2)
          .update();
    }
    var rules = new JdbcRuleSource(jdbc, new ConditionParser()).rules();

    assertThat(rules).hasSize(1);
    assertThat(rules.get(0).version()).isEqualTo(2);
    assertThat(rules.get(0).action().scoreContribution()).isEqualTo(50);
  }
}
