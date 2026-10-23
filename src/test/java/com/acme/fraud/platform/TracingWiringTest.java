package com.acme.fraud.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.fraud.FraudApplication;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = FraudApplication.class)
class TracingWiringTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    r.add("spring.kafka.listener.auto-startup", () -> "false");
    r.add("fraud.security.jwt-secret", () -> "test-secret-test-secret-test-secret-123");
    r.add("fraud.outbox.poll-interval-ms", () -> "3600000");
  }

  @Autowired Tracer tracer;

  @Test
  void tracerCreatesSpansWithIds() {
    Span span = tracer.nextSpan().name("probe").start();
    try {
      assertThat(span.context().traceId()).hasSize(32);
      assertThat(span.context().spanId()).hasSize(16);
    } finally {
      span.end();
    }
  }
}
