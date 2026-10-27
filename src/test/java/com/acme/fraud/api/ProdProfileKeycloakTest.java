package com.acme.fraud.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.fraud.FraudApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the application with the {@code prod} profile against a real Keycloak container that
 * imports {@code config/keycloak/fraud-realm.json}. Covers what the fake OIDC provider cannot:
 * Keycloak's own discovery document, signing keys, audience mapper and nested {@code
 * realm_access.roles} claim. Keycloak derives its issuer from the request host (hostname strict
 * mode is off), so the issuer the app validates is the URL the test fetched tokens from.
 */
@SpringBootTest(classes = FraudApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdProfileKeycloakTest {

  private static final String REALM = "fraud";
  private static final String CLIENT_ID = "fraud-api";

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
          .withCommand("start-dev", "--import-realm")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          .withEnv("KC_HOSTNAME_STRICT", "false")
          .withEnv("KC_HTTP_ENABLED", "true")
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("config/keycloak/fraud-realm.json")),
              "/opt/keycloak/data/import/fraud-realm.json")
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    POSTGRES.start();
    KEYCLOAK.start();
  }

  private static String issuer() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("JWT_ISSUER_URI", ProdProfileKeycloakTest::issuer);
    r.add("fraud.security.roles-claim", () -> "realm_access.roles");
    r.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> CLIENT_ID);
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    r.add("spring.kafka.listener.auto-startup", () -> "false");
    r.add("management.defaults.metrics.export.enabled", () -> "true");
    r.add("management.prometheus.metrics.export.enabled", () -> "true");
    r.add("fraud.outbox.poll-interval-ms", () -> "3600000");
  }

  @Autowired MockMvc mvc;

  /** Password-grant token from Keycloak for one of the realm's demo users. */
  private static String tokenFor(String username, String password) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=password&client_id="
                        + CLIENT_ID
                        + "&username="
                        + username
                        + "&password="
                        + password))
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("token endpoint: %s", response.body()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    return "Bearer " + body.get("access_token").asString();
  }

  @Test
  void rejectsRequestWithoutToken() throws Exception {
    mvc.perform(get("/api/v1/decisions")).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsGarbageToken() throws Exception {
    mvc.perform(get("/api/v1/decisions").header("Authorization", "Bearer not.a.jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void analystMapsFromNestedRealmRolesAndCanReadButNotAdminister() throws Exception {
    String analyst = tokenFor("analyst", "analyst");
    mvc.perform(get("/api/v1/decisions").header("Authorization", analyst))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/rules")
                .header("Authorization", analyst)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/actuator/prometheus").header("Authorization", analyst))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanReadAndScrapeMetrics() throws Exception {
    String admin = tokenFor("admin", "admin");
    mvc.perform(get("/api/v1/decisions").header("Authorization", admin)).andExpect(status().isOk());
    mvc.perform(get("/actuator/prometheus").header("Authorization", admin))
        .andExpect(status().isOk());
  }

  @Test
  void apiDocsAreDisabledInProd() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
  }
}
