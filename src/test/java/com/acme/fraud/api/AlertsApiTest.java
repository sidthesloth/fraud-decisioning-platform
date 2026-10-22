package com.acme.fraud.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.fraud.FraudApplication;
import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.MatchedRule;
import com.acme.fraud.evaluation.Outcome;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
class AlertsApiTest {

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
  }

  @Autowired MockMvc mvc;
  @Autowired DecisionStore decisions;
  @Autowired AlertStore alerts;

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

  private UUID newAlert(String txn) {
    Decision d =
        new Decision(
            txn,
            "cust_al",
            Outcome.DECLINE,
            90,
            List.of(new MatchedRule("R", 1, 90, "R")),
            List.of(),
            false,
            "b".repeat(64),
            Instant.parse("2026-10-17T18:00:00Z"),
            "test");
    UUID decisionId = decisions.save(d).decisionId();
    return alerts.createFor(decisionId, d).alertId();
  }

  private static final String BODY = "{\"status\":\"IN_REVIEW\",\"note\":\"looking\"}";

  @Test
  void unauthenticatedIs401() throws Exception {
    mvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
  }

  @Test
  void analystListsFilteredQueue() throws Exception {
    newAlert("txn_al_1");
    mvc.perform(
            get("/api/v1/alerts")
                .param("status", "OPEN")
                .header("Authorization", token("FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").isNumber())
        .andExpect(jsonPath("$.items[0].status").value("OPEN"));
  }

  @Test
  void analystDispositionsWithIfMatchAndGetsNewEtag() throws Exception {
    UUID id = newAlert("txn_al_2");
    mvc.perform(
            post("/api/v1/alerts/" + id + "/disposition")
                .header("Authorization", token("FRAUD_ANALYST"))
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.status").value("IN_REVIEW"));
  }

  @Test
  void missingIfMatchIs428() throws Exception {
    UUID id = newAlert("txn_al_3");
    mvc.perform(
            post("/api/v1/alerts/" + id + "/disposition")
                .header("Authorization", token("FRAUD_ANALYST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isPreconditionRequired());
  }

  @Test
  void staleVersionIs412() throws Exception {
    UUID id = newAlert("txn_al_4");
    mvc.perform(
            post("/api/v1/alerts/" + id + "/disposition")
                .header("Authorization", token("FRAUD_ANALYST"))
                .header("If-Match", "\"7\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isPreconditionFailed());
  }

  @Test
  void illegalTransitionIs409() throws Exception {
    UUID id = newAlert("txn_al_5");
    mvc.perform(
            post("/api/v1/alerts/" + id + "/disposition")
                .header("Authorization", token("FRAUD_ANALYST"))
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void wrongRoleIs403() throws Exception {
    UUID id = newAlert("txn_al_6");
    mvc.perform(
            post("/api/v1/alerts/" + id + "/disposition")
                .header("Authorization", token("OTHER"))
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }
}
