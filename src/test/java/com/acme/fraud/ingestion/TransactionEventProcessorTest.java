package com.acme.fraud.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.fraud.alert.internal.JdbcAlertStore;
import com.acme.fraud.decisions.internal.JdbcDecisionStore;
import com.acme.fraud.evaluation.DeterministicRiskScorer;
import com.acme.fraud.evaluation.FraudEvaluator;
import com.acme.fraud.evaluation.RuleEngine;
import com.acme.fraud.evaluation.Thresholds;
import com.acme.fraud.evaluation.internal.JdbcContextEnricher;
import com.acme.fraud.ingestion.TransactionEventProcessor.Result;
import com.acme.fraud.ingestion.internal.JdbcIngestionLog;
import com.acme.fraud.ruledomain.Condition;
import com.acme.fraud.ruledomain.Operator;
import com.acme.fraud.ruledomain.RuleAction;
import com.acme.fraud.ruledomain.RuleVersion;
import java.time.Clock;
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

class TransactionEventProcessorTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  private static JdbcClient jdbc;
  private static TransactionEventProcessor processor;
  private static volatile List<RuleVersion> rules;
  private static volatile boolean enrichmentDown;

  private static RuleVersion amountRule(int version, int score) {
    return new RuleVersion(
        "HIGH_VALUE",
        version,
        "High value",
        null,
        1,
        new Condition.Leaf("amount", Operator.GT, "5000"),
        new RuleAction(score, "HV"),
        true,
        null,
        null);
  }

  private static String event(String eventId, String txn, String amount) {
    return """
        {"specversion":"1.0","id":"%s","type":"com.acme.payments.transaction-categorized.v1",
         "source":"//mock","subject":"%s","time":"2026-10-17T18:00:00Z",
         "datacontenttype":"application/json",
         "data":{"transactionId":"%s","customerId":"cust_9","accountId":"acc_4","amount":"%s",
         "currency":"ZAR","merchantCategory":"MCC5999","merchantId":"m1","channel":"CARD_PRESENT",
         "occurredAt":"2026-10-17T17:59:00Z","location":{"country":"ZA","city":"Cape Town"},
         "deviceId":"dev_2"}}
        """
        .formatted(eventId, txn, txn, amount);
  }

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
    JdbcContextEnricher realEnricher = new JdbcContextEnricher(jdbc, "ZA");
    FraudEvaluator evaluator =
        new FraudEvaluator(
            f -> {
              if (enrichmentDown) {
                throw new com.acme.fraud.evaluation.EnrichmentUnavailableException("down", null);
              }
              return realEnricher.enrich(f);
            },
            new RuleEngine(),
            new DeterministicRiskScorer(),
            Thresholds.DEFAULT,
            Clock.systemUTC());
    processor =
        new TransactionEventProcessor(
            new CloudEventParser(json),
            new JdbcIngestionLog(jdbc),
            () -> rules,
            evaluator,
            new JdbcDecisionStore(jdbc, tx, json),
            new JdbcAlertStore(jdbc, tx, json, Clock.systemUTC()),
            tx);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    rules = List.of(amountRule(1, 50));
    enrichmentDown = false;
    for (String t :
        List.of(
            "alert_transitions",
            "alerts",
            "outbox",
            "decision_skipped_rules",
            "decision_matched_rules",
            "decisions",
            "transaction_history",
            "processed_events")) {
      jdbc.sql("DELETE FROM " + t).update();
    }
  }

  private static int count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }

  @Test
  void flaggedEventProducesDecisionAlertHistoryAndTwoOutboxRows() {
    assertThat(processor.process(event("evt_1", "txn_1", "6000.00"))).isEqualTo(Result.PROCESSED);

    assertThat(count("decisions")).isOne();
    assertThat(count("decision_matched_rules")).isOne();
    assertThat(count("alerts")).isOne();
    assertThat(count("transaction_history")).isOne();
    assertThat(count("outbox")).isEqualTo(2);
  }

  @Test
  void approvedEventCreatesNoAlert() {
    processor.process(event("evt_1", "txn_1", "10.00"));

    assertThat(count("decisions")).isOne();
    assertThat(count("alerts")).isZero();
    assertThat(count("outbox")).isOne();
  }

  @Test
  void sameEventTwiceYieldsOneDecision() {
    String e = event("evt_1", "txn_1", "6000.00");
    processor.process(e);

    assertThat(processor.process(e)).isEqualTo(Result.DUPLICATE);
    assertThat(count("decisions")).isOne();
    assertThat(count("outbox")).isEqualTo(2);
  }

  @Test
  void oldDecisionKeepsOldRuleSnapshotAfterNewRuleVersion() {
    processor.process(event("evt_1", "txn_1", "6000.00"));
    rules = List.of(amountRule(2, 90));
    processor.process(event("evt_2", "txn_2", "6000.00"));

    assertThat(matchedVersion("txn_1")).isEqualTo("1:50");
    assertThat(matchedVersion("txn_2")).isEqualTo("2:90");
  }

  private static String matchedVersion(String txn) {
    return jdbc.sql(
            "SELECT m.rule_version || ':' || m.contribution FROM decision_matched_rules m"
                + " JOIN decisions d USING (decision_id) WHERE d.transaction_id = :t")
        .param("t", txn)
        .query(String.class)
        .single();
  }

  @Test
  void enrichmentOutageStillProducesDegradedDecision() {
    enrichmentDown = true;
    assertThat(processor.process(event("evt_1", "txn_1", "6000.00"))).isEqualTo(Result.PROCESSED);

    assertThat(
            jdbc.sql("SELECT enrichment_degraded FROM decisions WHERE transaction_id = 'txn_1'")
                .query(Boolean.class)
                .single())
        .isTrue();
    assertThat(count("decisions")).isOne();
  }

  @Test
  void poisonMessageIsRejectedWithoutSideEffects() {
    assertThatThrownBy(() -> processor.process("{not json"))
        .isInstanceOf(InvalidEventException.class);
    assertThatThrownBy(() -> processor.process(event("evt_1", "txn_1", "abc")))
        .isInstanceOf(InvalidEventException.class);
    assertThat(count("processed_events")).isZero();
    assertThat(count("decisions")).isZero();
  }
}
