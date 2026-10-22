package com.acme.fraud.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.fraud.FraudApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
class DlqApiTest {

  private static final String SECRET = "test-secret-test-secret-test-secret-123";
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
    r.add("fraud.security.jwt-secret", () -> SECRET);
    r.add("fraud.outbox.poll-interval-ms", () -> "3600000");
    r.add("fraud.api.dlq-replay-limit-per-second", () -> "1000");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;

  private static String token(String... roles) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("analyst_1")
            .claim("roles", List.of(roles))
            .expirationTime(new Date(System.currentTimeMillis() + 60_000))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return "Bearer " + jwt.serialize();
  }

  private void deadLetter(String original) {
    jdbc.sql(
            "INSERT INTO outbox (id, topic, message_key, payload, published_at) VALUES"
                + " (gen_random_uuid(), 'fraud.dlq.v1', 'k', jsonb_build_object('originalMessage',"
                + " :o::text), now())")
        .param("o", original)
        .update();
  }

  private int inputRows() {
    return jdbc.sql(
            "SELECT count(*) FROM outbox WHERE topic = 'payments.transactions.categorized.v1'")
        .query(Integer.class)
        .single();
  }

  @Test
  void analystCannotReplay() throws Exception {
    mvc.perform(post("/api/v1/dlq/replay").header("Authorization", token("FRAUD_ANALYST")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminReplaysValidJsonOnceAndSkipsUnparseable() throws Exception {
    deadLetter("{\"id\":\"evt_r1\",\"type\":\"x\"}");
    deadLetter("{not json");

    mvc.perform(post("/api/v1/dlq/replay").header("Authorization", token("FRAUD_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(1))
        .andExpect(jsonPath("$.skipped").value(1));
    org.assertj.core.api.Assertions.assertThat(inputRows()).isEqualTo(1);

    mvc.perform(
            post("/api/v1/dlq/replay")
                .header("Authorization", token("FRAUD_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":10}"))
        .andExpect(jsonPath("$.replayed").value(0));
    org.assertj.core.api.Assertions.assertThat(inputRows()).isEqualTo(1);
  }

  @Test
  void invalidLimitIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/dlq/replay")
                .header("Authorization", token("FRAUD_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":0}"))
        .andExpect(status().isBadRequest());
  }
}
