package com.acme.fraud.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesClaimConverterTest {

  private static Jwt jwt(Map<String, Object> claims) {
    return new Jwt(
        "t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
  }

  private static List<String> roles(String path, Map<String, Object> claims) {
    return new RolesClaimConverter(path)
        .convert(jwt(claims)).stream().map(GrantedAuthority::getAuthority).toList();
  }

  @Test
  void readsTopLevelCollection() {
    assertThat(roles("roles", Map.of("sub", "u", "roles", List.of("FRAUD_ADMIN", "FRAUD_ANALYST"))))
        .containsExactly("ROLE_FRAUD_ADMIN", "ROLE_FRAUD_ANALYST");
  }

  @Test
  void readsNestedPathLikeKeycloak() {
    Map<String, Object> claims =
        Map.of("sub", "u", "realm_access", Map.of("roles", List.of("FRAUD_ANALYST")));
    assertThat(roles("realm_access.roles", claims)).containsExactly("ROLE_FRAUD_ANALYST");
  }

  @Test
  void readsSpaceDelimitedString() {
    assertThat(roles("scope", Map.of("sub", "u", "scope", "FRAUD_ANALYST  FRAUD_ADMIN")))
        .containsExactly("ROLE_FRAUD_ANALYST", "ROLE_FRAUD_ADMIN");
  }

  @Test
  void missingOrMalformedClaimYieldsNoAuthorities() {
    assertThat(roles("roles", Map.of("sub", "u"))).isEmpty();
    assertThat(roles("realm_access.roles", Map.of("sub", "u", "realm_access", "oops"))).isEmpty();
    assertThat(roles("roles", Map.of("sub", "u", "roles", 42))).isEmpty();
    assertThat(roles("roles", Map.of("sub", "u", "roles", List.of(1, " ")))).isEmpty();
  }

  @Test
  void rejectsBlankClaimPath() {
    assertThatThrownBy(() -> new RolesClaimConverter(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
