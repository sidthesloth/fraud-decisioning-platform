package com.acme.fraud.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.fraud.alert.AlertStatus;
import com.acme.fraud.alert.AlertStore.Alert;
import com.acme.fraud.alert.AlertStore.CreateResult;
import com.acme.fraud.alert.AlertStore.IllegalTransitionException;
import com.acme.fraud.alert.AlertStore.StaleAlertException;
import com.acme.fraud.alert.Severity;
import com.acme.fraud.decisions.DecisionStore.SaveResult;
import com.acme.fraud.decisions.internal.JdbcDecisionStore;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.Outcome;
import java.time.Clock;
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

class JdbcAlertStoreTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  private static JdbcClient jdbc;
  private static JdbcDecisionStore decisions;
  private static JdbcAlertStore alerts;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    JsonMapper json = JsonMapper.builder().build();
    decisions = new JdbcDecisionStore(jdbc, tx, json);
    alerts = new JdbcAlertStore(jdbc, tx, json, Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM alert_transitions").update();
    jdbc.sql("DELETE FROM alerts").update();
    jdbc.sql("DELETE FROM outbox").update();
    jdbc.sql("DELETE FROM decision_skipped_rules").update();
    jdbc.sql("DELETE FROM decision_matched_rules").update();
    jdbc.sql("DELETE FROM decisions").update();
  }

  private static Decision decision(String txn, Outcome outcome, int score) {
    return new Decision(
        txn,
        "cust_9",
        outcome,
        score,
        List.of(),
        List.of(),
        false,
        "a".repeat(64),
        Instant.parse("2026-10-17T18:00:00Z"),
        "engine-1");
  }

  private static CreateResult createAlert(String txn, Outcome outcome, int score) {
    Decision d = decision(txn, outcome, score);
    SaveResult saved = decisions.save(d);
    return alerts.createFor(saved.decisionId(), d);
  }

  @Test
  void createsOpenAlertWithAuditAndOutboxRow() {
    CreateResult r = createAlert("txn_1", Outcome.DECLINE, 99);

    Alert a = alerts.findById(r.alertId()).orElseThrow();
    assertThat(r.created()).isTrue();
    assertThat(a.status()).isEqualTo(AlertStatus.OPEN);
    assertThat(a.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox WHERE topic = 'fraud.alerts.v1'"
                        + " AND message_key = 'txn_1' AND payload->>'severity' = 'CRITICAL'")
                .query(Integer.class)
                .single())
        .isOne();
    assertThat(jdbc.sql("SELECT count(*) FROM alert_transitions").query(Integer.class).single())
        .isOne();
  }

  @Test
  void createIsIdempotentPerDecision() {
    Decision d = decision("txn_1", Outcome.REVIEW, 50);
    SaveResult saved = decisions.save(d);
    CreateResult first = alerts.createFor(saved.decisionId(), d);
    CreateResult second = alerts.createFor(saved.decisionId(), d);

    assertThat(second.created()).isFalse();
    assertThat(second.alertId()).isEqualTo(first.alertId());
    assertThat(jdbc.sql("SELECT count(*) FROM alerts").query(Integer.class).single()).isOne();
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'fraud.alerts.v1'")
                .query(Integer.class)
                .single())
        .isOne();
  }

  @Test
  void rejectsApprovedDecision() {
    Decision d = decision("txn_1", Outcome.APPROVE, 0);
    SaveResult saved = decisions.save(d);
    assertThatThrownBy(() -> alerts.createFor(saved.decisionId(), d))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void transitionsFollowLifecycleAndBumpVersion() {
    CreateResult r = createAlert("txn_1", Outcome.REVIEW, 50);

    Alert a = alerts.transition(r.alertId(), AlertStatus.IN_REVIEW, "analyst_1", "taking it", 0);
    assertThat(a.status()).isEqualTo(AlertStatus.IN_REVIEW);
    assertThat(a.version()).isEqualTo(1);

    assertThatThrownBy(
            () -> alerts.transition(r.alertId(), AlertStatus.CLOSED, "analyst_1", null, 1))
        .isInstanceOf(IllegalTransitionException.class);

    alerts.transition(r.alertId(), AlertStatus.FALSE_POSITIVE, "analyst_1", null, 1);
    assertThat(jdbc.sql("SELECT count(*) FROM alert_transitions").query(Integer.class).single())
        .isEqualTo(3);
  }

  @Test
  void staleVersionIsRejected() {
    CreateResult r = createAlert("txn_1", Outcome.REVIEW, 50);
    alerts.transition(r.alertId(), AlertStatus.IN_REVIEW, "a", null, 0);

    assertThatThrownBy(
            () -> alerts.transition(r.alertId(), AlertStatus.CONFIRMED_FRAUD, "b", null, 0))
        .isInstanceOf(StaleAlertException.class);
  }
}
