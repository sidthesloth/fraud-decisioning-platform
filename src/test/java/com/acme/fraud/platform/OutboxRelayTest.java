package com.acme.fraud.platform;

import static org.assertj.core.api.Assertions.assertThat;

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
  private static DataSource dataSource;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    dataSource = ds;
    jdbc = JdbcClient.create(ds);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM outbox").update();
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
  void concurrentRelaysNeverPublishTheSameRow() throws Exception {
    for (int i = 1; i <= 4; i++) {
      outbox("k" + i, i);
    }
    var txTemplate =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    List<String> keysA = java.util.Collections.synchronizedList(new ArrayList<>());
    List<String> keysB = java.util.Collections.synchronizedList(new ArrayList<>());
    OutboxRelay relayA =
        new OutboxRelay(
            jdbc,
            (t, k, p) -> {
              entered.countDown();
              try {
                if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                  throw new IllegalStateException("timeout");
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              keysA.add(k);
            },
            2,
            txTemplate);
    OutboxRelay relayB = new OutboxRelay(jdbc, (t, k, p) -> keysB.add(k), 10, txTemplate);

    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var futureA = executor.submit(relayA::relayBatch);
      assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      assertThat(relayB.relayBatch()).isEqualTo(2);
      assertThat(keysB).containsExactly("k3", "k4");

      release.countDown();
      assertThat(futureA.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2);
      assertThat(keysA).containsExactly("k1", "k2");
      assertThat(unpublished()).isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
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
}
