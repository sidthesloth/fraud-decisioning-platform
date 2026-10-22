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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
class OpenApiAndRateLimitApiTest {

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
    r.add("management.defaults.metrics.export.enabled", () -> "true");
    r.add("management.prometheus.metrics.export.enabled", () -> "true");
    r.add("fraud.outbox.poll-interval-ms", () -> "3600000");
    r.add("fraud.api.dlq-replay-limit-per-second", () -> "1");
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

  @Test
  void openApiDocsArePublicAndDeclareBearerAuth() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/decisions/{id}']").exists())
        .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
  }

  @Test
  void dlqReplayIsRateLimited() throws Exception {
    boolean limited = false;
    for (int i = 0; i < 5 && !limited; i++) {
      limited =
          mvc.perform(post("/api/v1/dlq/replay").header("Authorization", token("FRAUD_ADMIN")))
                  .andReturn()
                  .getResponse()
                  .getStatus()
              == 429;
    }
    org.assertj.core.api.Assertions.assertThat(limited).isTrue();
  }
}
