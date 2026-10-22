package com.acme.fraud.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.ingestion.internal.JdbcRuleSource;
import com.acme.fraud.ruledomain.ConditionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class OutboxRelayTest {

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
    jdbc.sql("DELETE FROM outbox").update();
    jdbc.sql("DELETE FROM fraud_rule_versions").update();
    jdbc.sql("DELETE FROM fraud_rules").update();
  }

  private static void outbox(String key, long offsetMs) {
    jdbc.sql(
            "INSERT INTO outbox (id, topic, message_key, payload, created_at) VALUES (:id, 't',"
                + " :k, '{\"a\":1}'::jsonb, (:at))")
        .param("id", UUID.randomUUID())
        .param("k", key)
        .param(
            "at",
            java.sql.Timestamp.from(
                java.time.Instant.parse("2026-10-17T18:00:00Z").plusMillis(offsetMs)))
        .update();
  }

  private static int unpublished() {
    return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
        .query(Integer.class)
        .single();
  }

  @Test
  void publishesInOrderAndMarksPublished() {
    outbox("k2", 2);
    outbox("k1", 1);
    List<String> keys = new ArrayList<>();
    OutboxRelay relay = new OutboxRelay(jdbc, (t, k, p) -> keys.add(k), 10);

    assertThat(relay.relayBatch()).isEqualTo(2);
    assertThat(keys).containsExactly("k1", "k2");
    assertThat(unpublished()).isZero();
    assertThat(relay.relayBatch()).isZero();
  }

  @Test
  void failureLeavesRowsUnpublishedAndStopsBatch() {
    outbox("k1", 1);
    outbox("k2", 2);
    OutboxRelay relay =
        new OutboxRelay(
            jdbc,
            (t, k, p) -> {
              throw new IllegalStateException("broker down");
            },
            10);

    assertThat(relay.relayBatch()).isZero();
    assertThat(unpublished()).isEqualTo(2);
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
