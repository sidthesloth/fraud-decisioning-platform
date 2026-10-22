package com.acme.fraud.api;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server. Roles come from the {@code roles} claim (e.g. {@code FRAUD_ANALYST}) and map
 * to {@code ROLE_*} authorities. The decoder verifies HS256 tokens signed with a shared secret
 * supplied via {@code FRAUD_JWT_SECRET}; production would swap this bean for an issuer-based
 * decoder without touching the filter chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

  public static final String ANALYST = "FRAUD_ANALYST";
  public static final String ADMIN = "FRAUD_ADMIN";

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .hasAnyRole(ANALYST, ADMIN)
                    .requestMatchers(HttpMethod.POST, "/api/v1/alerts/*/disposition")
                    .hasAnyRole(ANALYST, ADMIN)
                    .requestMatchers("/api/**")
                    .hasRole(ADMIN)
                    .anyRequest()
                    .hasRole(ADMIN))
        .oauth2ResourceServer(
            oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .headers(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${fraud.security.jwt-secret}") String secret) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) {
      throw new IllegalStateException("fraud.security.jwt-secret must be at least 32 bytes");
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> subjectRequired =
        jwt ->
            jwt.getSubject() == null
                ? OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "sub claim is required", null))
                : OAuth2TokenValidatorResult.success();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), subjectRequired));
    return decoder;
  }
}
