package com.acme.fraud.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.evaluation.EnrichedAggregates;
import com.acme.fraud.evaluation.TransactionFacts;
import java.math.BigDecimal;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class JdbcContextEnricherTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static final Instant NOW = Instant.parse("2026-10-17T18:00:00Z");

  private static JdbcClient jdbc;
  private static JdbcContextEnricher enricher;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds);
    enricher = new JdbcContextEnricher(jdbc, "ZA");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM transaction_history").update();
  }

  private static void insert(String id, String account, String amount, String device, Instant at) {
    jdbc.sql(
            "INSERT INTO transaction_history (transaction_id, customer_id, account_id, amount,"
                + " currency, device_id, country, occurred_at) VALUES (:id, 'c1', :acc, :amt,"
                + " 'ZAR', :dev, 'ZA', :at)")
        .param("id", id)
        .param("acc", account)
        .param("amt", new BigDecimal(amount))
        .param("dev", device)
        .param("at", java.sql.Timestamp.from(at))
        .update();
  }

  private static TransactionFacts facts(String country, String device) {
    return new TransactionFacts(
        "txn_now",
        "c1",
        "acc_1",
        new BigDecimal("100.00"),
        "ZAR",
        "MCC5999",
        "m1",
        "CARD_PRESENT",
        NOW,
        country,
        "X",
        device);
  }

  @Test
  void emptyHistoryYieldsZeroAggregatesAndNewDevice() {
    EnrichedAggregates a = enricher.enrich(facts("ZA", "dev_1"));
    assertThat(a.lastHourCount()).isZero();
    assertThat(a.last24hAmount()).isEqualByComparingTo("0");
    assertThat(a.newDevice()).isTrue();
    assertThat(a.crossBorder()).isFalse();
    assertThat(a.riskTier()).isNull();
    assertThat(a.geoVelocity()).isNull();
  }

  @Test
  void countsWindowsPerAccountAndExcludesCurrentTransaction() {
    insert("t1", "acc_1", "10.00", "dev_1", NOW.minusSeconds(600));
    insert("t2", "acc_1", "20.00", "dev_1", NOW.minusSeconds(3000));
    insert("t3", "acc_1", "30.00", "dev_1", NOW.minusSeconds(7200));
    insert("t4", "acc_1", "40.00", "dev_1", NOW.minusSeconds(90000));
    insert("t5", "acc_2", "999.00", "dev_9", NOW.minusSeconds(60));
    insert("txn_now", "acc_1", "100.00", "dev_1", NOW);

    EnrichedAggregates a = enricher.enrich(facts("NG", "dev_1"));

    assertThat(a.lastHourCount()).isEqualTo(2);
    assertThat(a.last24hAmount()).isEqualByComparingTo("60.00");
    assertThat(a.newDevice()).isFalse();
    assertThat(a.crossBorder()).isTrue();
  }

  @Test
  void unknownDeviceIsNew() {
    insert("t1", "acc_1", "10.00", "dev_1", NOW.minusSeconds(60));
    assertThat(enricher.enrich(facts("ZA", "dev_2")).newDevice()).isTrue();
  }

  @Test
  void missingDeviceOrCountryStayUnavailable() {
    EnrichedAggregates a = enricher.enrich(facts(null, null));
    assertThat(a.newDevice()).isNull();
    assertThat(a.crossBorder()).isNull();
  }
}
