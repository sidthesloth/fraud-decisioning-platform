package com.acme.fraud.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.ruledomain.ConditionParser;
import com.acme.fraud.ruledomain.RuleVersion;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class DemoSeedRulesTest {

  @Test
  void devSeedRulesMigrateAndParse() {
    try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
      pg.start();
      DataSource ds =
          new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration", "classpath:db/seed-dev")
          .load()
          .migrate();

      List<RuleVersion> rules =
          new JdbcRuleSource(
                  JdbcClient.create(ds), new ConditionParser(JsonMapper.builder().build()))
              .rules();

      assertThat(rules)
          .extracting(RuleVersion::ruleId)
          .containsExactly("HIGH_VALUE", "CROSS_BORDER_CNP", "VELOCITY_1H");
    }
  }
}
