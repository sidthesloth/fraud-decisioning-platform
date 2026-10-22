package com.acme.fraud.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
class RulesApiTest {

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
    r.add("fraud.api.rule-admin-limit-per-second", () -> "1000");
  }

  @Autowired MockMvc mvc;

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

  private static final String RULE =
      "{\"ruleId\":\"RT1\",\"version\":{\"name\":\"n\",\"priority\":1,\"score\":50,"
          + "\"reasonCode\":\"RT\",\"condition\":{\"field\":\"amount\",\"op\":\"GT\","
          + "\"value\":\"100\"}}}";
  private static final String V2 =
      "{\"name\":\"n2\",\"priority\":1,\"score\":60,\"reasonCode\":\"RT\","
          + "\"condition\":{\"field\":\"amount\",\"op\":\"GT\",\"value\":\"200\"}}";

  @Test
  void analystCannotCreate() throws Exception {
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", token("FRAUD_ANALYST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(RULE))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminLifecycleKeepsVersionsImmutable() throws Exception {
    String admin = token("FRAUD_ADMIN");
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(RULE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.enabled").value(false));
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(RULE))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/v1/rules/RT1/versions")
                .header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(V2))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(2));
    mvc.perform(post("/api/v1/rules/RT1/versions/1:activate").header("Authorization", admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
    mvc.perform(get("/api/v1/rules/RT1/versions").header("Authorization", token("FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].score").value(50))
        .andExpect(jsonPath("$[1].enabled").value(false));
    mvc.perform(post("/api/v1/rules/RT1/versions/1:deactivate").header("Authorization", admin))
        .andExpect(jsonPath("$.enabled").value(false));
    mvc.perform(get("/api/v1/rules").header("Authorization", token("FRAUD_ANALYST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.ruleId=='RT1')].version").value(2));
  }

  @Test
  void invalidDslIs400() throws Exception {
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", token("FRAUD_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(RULE.replace("\"GT\"", "\"BOGUS\"").replace("RT1", "RT2")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownRuleIs404() throws Exception {
    mvc.perform(get("/api/v1/rules/NOPE/versions").header("Authorization", token("FRAUD_ANALYST")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/rules/NOPE/versions/1:activate")
                .header("Authorization", token("FRAUD_ADMIN")))
        .andExpect(status().isNotFound());
  }
}
