package com.acme.fraud.decisions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.decisions.DecisionStore.SaveResult;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.MatchedRule;
import com.acme.fraud.evaluation.Outcome;
import com.acme.fraud.evaluation.SkippedRule;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class JdbcDecisionStoreTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  private static JdbcClient jdbc;
  private static JdbcDecisionStore store;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds);
    store =
        new JdbcDecisionStore(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            JsonMapper.builder().build());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM outbox").update();
    jdbc.sql("DELETE FROM decision_skipped_rules").update();
    jdbc.sql("DELETE FROM decision_matched_rules").update();
    jdbc.sql("DELETE FROM decisions").update();
  }

  private static Decision decision(String txn, int ruleVersion) {
    return new Decision(
        txn,
        "cust_9",
        Outcome.REVIEW,
        55,
        List.of(new MatchedRule("HIGH_VALUE", ruleVersion, 55, "HV")),
        List.of(new SkippedRule("VELOCITY", 1, SkippedRule.Reason.ENRICHMENT_DEGRADED)),
        true,
        "a".repeat(64),
        Instant.parse("2026-10-17T18:00:00Z"),
        "engine-1");
  }

  @Test
  void savesAndReadsBackWithSnapshotsAndOutboxRow() {
    Decision d = decision("txn_1", 3);

    SaveResult r = store.save(d);

    assertThat(r.created()).isTrue();
    assertThat(store.findById(r.decisionId()))
        .get()
        .satisfies(s -> assertThat(s.decision()).isEqualTo(d));
    assertThat(store.findByTransactionId("txn_1")).isPresent();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox WHERE topic = 'fraud.evaluations.v1'"
                        + " AND message_key = 'txn_1' AND published_at IS NULL"
                        + " AND payload->>'outcome' = 'REVIEW'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void savingSameTransactionTwiceKeepsOneDecisionAndOneOutboxRow() {
    SaveResult first = store.save(decision("txn_1", 1));
    SaveResult second = store.save(decision("txn_1", 2));

    assertThat(second.created()).isFalse();
    assertThat(second.decisionId()).isEqualTo(first.decisionId());
    assertThat(jdbc.sql("SELECT count(*) FROM decisions").query(Integer.class).single()).isOne();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single()).isOne();
    assertThat(store.findById(first.decisionId()).orElseThrow().decision().matchedRules())
        .extracting(MatchedRule::version)
        .containsExactly(1);
  }

  @Test
  void unknownIdIsEmpty() {
    assertThat(store.findByTransactionId("nope")).isEmpty();
  }
}
