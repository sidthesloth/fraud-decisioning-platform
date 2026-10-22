package com.acme.fraud.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

class JdbcDeadLetterSinkTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  private static JdbcClient jdbc;
  private static JdbcDeadLetterSink sink;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    DataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds);
    sink = new JdbcDeadLetterSink(jdbc, JsonMapper.builder().build());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void writesUnpublishedDlqOutboxRowWithErrorMetadata() {
    sink.send("{\"bad\":true}", "InvalidEventException", "missing id", 1);

    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM outbox WHERE topic = 'fraud.dlq.v1'"
                        + " AND published_at IS NULL"
                        + " AND payload->>'originalMessage' = '{\"bad\":true}'"
                        + " AND payload->>'errorType' = 'InvalidEventException'"
                        + " AND payload->>'errorMessage' = 'missing id'"
                        + " AND (payload->>'attempts')::int = 1")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
