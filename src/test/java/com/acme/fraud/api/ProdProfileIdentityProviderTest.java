package com.acme.fraud.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.fraud.FraudApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the application with the {@code prod} profile against a local OIDC provider. No {@code
 * fraud.security.jwt-secret} is set, so the issuer-based decoder (discovery, JWKS, {@code iss},
 * {@code exp}) is the only token validation in play.
 */
@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdProfileIdentityProviderTest {

  private static final FakeOidcProvider IDP = new FakeOidcProvider();
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static final String PUBLIC_DEV_SECRET = "dev-only-secret-dev-only-secret-0123456789";

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("JWT_ISSUER_URI", IDP::issuer);
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    r.add("spring.kafka.listener.auto-startup", () -> "false");
    r.add("management.defaults.metrics.export.enabled", () -> "true");
    r.add("management.prometheus.metrics.export.enabled", () -> "true");
    r.add("fraud.outbox.poll-interval-ms", () -> "3600000");
  }

  @AfterAll
  static void stop() {
    IDP.close();
  }

  @Autowired MockMvc mvc;
  @Autowired ApplicationContext context;
  @Autowired MeterRegistry meters;

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private String analyst() {
    return bearer(IDP.sign(IDP.validClaims("analyst_1", "FRAUD_ANALYST").build()));
  }

  private String admin() {
    return bearer(IDP.sign(IDP.validClaims("admin_1", "FRAUD_ADMIN").build()));
  }

  @Test
  void usesIssuerBasedDecoderNotSharedSecret() {
    assertThat(context.getBeansOfType(Hs256JwtDecoderConfiguration.class)).isEmpty();
  }

  @Test
  void rejectsRequestWithoutToken() throws Exception {
    double before = failures("unauthenticated");
    mvc.perform(get("/api/v1/decisions")).andExpect(status().isUnauthorized());
    assertThat(failures("unauthenticated")).isEqualTo(before + 1);
  }

  @Test
  void countsAccessDeniedResponses() throws Exception {
    double before = failures("forbidden");
    mvc.perform(get("/actuator/prometheus").header("Authorization", analyst()))
        .andExpect(status().isForbidden());
    assertThat(failures("forbidden")).isEqualTo(before + 1);
  }

  private double failures(String type) {
    return meters.counter("fraud.security.failures.total", "type", type).count();
  }

  @Test
  void analystTokenCanReadButNotAdminister() throws Exception {
    mvc.perform(get("/api/v1/decisions").header("Authorization", analyst()))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", analyst())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/actuator/prometheus").header("Authorization", analyst()))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminTokenIsAccepted() throws Exception {
    mvc.perform(get("/api/v1/decisions").header("Authorization", admin()))
        .andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus").header("Authorization", admin()))
        .andExpect(status().isOk());
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    JWTClaimsSet expired =
        IDP.validClaims("admin_1", "FRAUD_ADMIN")
            .expirationTime(new Date(System.currentTimeMillis() - 600_000))
            .build();
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(IDP.sign(expired))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsTokenFromAnotherIssuer() throws Exception {
    JWTClaimsSet wrongIssuer =
        IDP.validClaims("admin_1", "FRAUD_ADMIN")
            .issuer("https://evil.example/realms/fraud")
            .build();
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(IDP.sign(wrongIssuer))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsTokenSignedWithUntrustedKey() throws Exception {
    String forged =
        FakeOidcProvider.sign(
            IDP.validClaims("admin_1", "FRAUD_ADMIN").build(), FakeOidcProvider.rogueKey());
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(forged)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsTokenSignedWithThePublicDevSecret() throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256), IDP.validClaims("dev-admin", "FRAUD_ADMIN").build());
    jwt.sign(new MACSigner(PUBLIC_DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(jwt.serialize())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validTokenWithoutRolesClaimIsForbidden() throws Exception {
    JWTClaimsSet noRoles =
        new JWTClaimsSet.Builder()
            .issuer(IDP.issuer())
            .subject("user_1")
            .expirationTime(new Date(System.currentTimeMillis() + 300_000))
            .build();
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(IDP.sign(noRoles))))
        .andExpect(status().isForbidden());
  }

  /**
   * With the default {@code fraud.security.roles-claim=roles}, roles nested elsewhere are not
   * mapped: the user is authenticated but has no access. An IdP that nests them (for example
   * Keycloak's {@code realm_access.roles}) needs {@code FRAUD_SECURITY_ROLES_CLAIM} set to that
   * path (see {@code RolesClaimConverterTest}).
   */
  @Test
  void rolesNestedInRealmAccessAreNotMapped() throws Exception {
    JWTClaimsSet keycloakStyle =
        new JWTClaimsSet.Builder()
            .issuer(IDP.issuer())
            .subject("user_1")
            .claim("realm_access", Map.of("roles", List.of("FRAUD_ADMIN")))
            .expirationTime(new Date(System.currentTimeMillis() + 300_000))
            .build();
    mvc.perform(get("/api/v1/decisions").header("Authorization", bearer(IDP.sign(keycloakStyle))))
        .andExpect(status().isForbidden());
  }

  @Test
  void swaggerAndApiDocsAreDisabled() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
  }
}
