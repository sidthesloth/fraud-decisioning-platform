package com.acme.fraud.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.fraud.FraudApplication;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 401/403/200 matrix and read behaviour of the decisions API. */
@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
class DecisionsApiTest {

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
  @Autowired DecisionStore store;

  private static String token(String subject, String... roles) throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .claim("roles", List.of(roles))
            .expirationTime(new Date(System.currentTimeMillis() + 60_000))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
    return "Bearer " + jwt.serialize();
  }

  private UUID save(String txn, String customer, Outcome outcome) {
    Decision d =
        new Decision(
            txn,
            customer,
            outcome,
            85,
            List.of(new MatchedRule("HIGH_VALUE", 2, 85, "HV")),
            List.of(),
            false,
            "a".repeat(64),
            Instant.parse("2026-10-17T18:00:00Z"),
            "test");
    return store.save(d).decisionId();
  }

  @Test
  void withoutTokenIs401() throws Exception {
    mvc.perform(get("/api/v1/decisions")).andExpect(status().isUnauthorized());
  }

  @Test
  void withoutRequiredRoleIs403() throws Exception {
    mvc.perform(get("/api/v1/decisions").header("Authorization", token("u", "SOMETHING_ELSE")))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidSignatureIs401() throws Exception {
    mvc.perform(get("/api/v1/decisions").header("Authorization", "Bearer abc.def.ghi"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void analystReadsDecisionWithMatchedRuleVersions() throws Exception {
    UUID id = save("txn_api_1", "cust_api_1", Outcome.DECLINE);
    mvc.perform(get("/api/v1/decisions/" + id).header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("DECLINE"))
        .andExpect(jsonPath("$.matchedRules[0].ruleId").value("HIGH_VALUE"))
        .andExpect(jsonPath("$.matchedRules[0].version").value(2));
  }

  @Test
  void unknownDecisionIs404ProblemDetail() throws Exception {
    mvc.perform(
            get("/api/v1/decisions/" + UUID.randomUUID())
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isNotFound());
  }

  @Test
  void searchFiltersAndPaginates() throws Exception {
    save("txn_api_2", "cust_api_2", Outcome.REVIEW);
    save("txn_api_3", "cust_api_2", Outcome.DECLINE);
    mvc.perform(
            get("/api/v1/decisions")
                .param("customerId", "cust_api_2")
                .param("outcome", "REVIEW")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].transactionId").value("txn_api_2"));
    mvc.perform(
            get("/api/v1/decisions")
                .param("customerId", "cust_api_2")
                .param("size", "1")
                .header("Authorization", token("a", "FRAUD_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void badPageSizeIs400() throws Exception {
    mvc.perform(
            get("/api/v1/decisions")
                .param("size", "0")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void statusReportsPersistedUntilPublished() throws Exception {
    UUID id = save("txn_api_st", "cust_api_st", Outcome.REVIEW);
    mvc.perform(
            get("/api/v1/decisions/" + id + "/status")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PERSISTED"))
        .andExpect(jsonPath("$.evaluationPublished").value(false));
    mvc.perform(
            get("/api/v1/decisions/" + UUID.randomUUID() + "/status")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isNotFound());
  }

  @Test
  void metricsSummaryCountsOutcomes() throws Exception {
    mvc.perform(
            get("/api/v1/metrics-summary")
                .param("hours", "744")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.byOutcome.APPROVE").exists())
        .andExpect(jsonPath("$.windowHours").value(744));
    mvc.perform(
            get("/api/v1/metrics-summary")
                .param("hours", "0")
                .header("Authorization", token("a", "FRAUD_ANALYST")))
        .andExpect(status().isBadRequest());
  }
}
