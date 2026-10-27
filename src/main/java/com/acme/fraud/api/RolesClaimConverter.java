package com.acme.fraud.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a roles claim to {@code ROLE_*} authorities. The claim is addressed by a dotted path, so a
 * nested claim such as Keycloak's {@code realm_access.roles} works as well as a top-level {@code
 * roles}. The claim value may be a collection of strings or a single space-delimited string. A
 * missing or malformed claim yields no authorities (the caller is then authenticated but
 * forbidden).
 */
final class RolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final String PREFIX = "ROLE_";

  private final String[] path;

  RolesClaimConverter(String claimPath) {
    if (claimPath == null || claimPath.isBlank()) {
      throw new IllegalArgumentException("fraud.security.roles-claim must not be blank");
    }
    this.path = claimPath.trim().split("\\.");
  }

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Object value = jwt.getClaims();
    for (String segment : path) {
      if (!(value instanceof Map<?, ?> map)) {
        return List.of();
      }
      value = map.get(segment);
    }
    List<GrantedAuthority> authorities = new ArrayList<>();
    if (value instanceof String s) {
      for (String role : s.trim().split("\\s+")) {
        add(authorities, role);
      }
    } else if (value instanceof Collection<?> roles) {
      for (Object role : roles) {
        if (role instanceof String s) {
          add(authorities, s);
        }
      }
    }
    return authorities;
  }

  private static void add(List<GrantedAuthority> authorities, String role) {
    if (!role.isBlank()) {
      authorities.add(new SimpleGrantedAuthority(PREFIX + role));
    }
  }
}
