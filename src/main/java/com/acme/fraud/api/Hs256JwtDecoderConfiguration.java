package com.acme.fraud.api;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Shared-secret (HS256) decoder for local development and tests. It exists only when {@code
 * fraud.security.jwt-secret} is set. When it is absent, Spring Boot's issuer-based decoder is used
 * instead ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri} or {@code jwk-set-uri}),
 * which is the production path. The publicly documented dev secret is rejected outside the {@code
 * dev} profile.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${fraud.security.jwt-secret:}'.isEmpty()")
class Hs256JwtDecoderConfiguration {

  static final String DEV_SECRET = "dev-only-secret-dev-only-secret-0123456789";

  @Bean
  JwtDecoder jwtDecoder(@Value("${fraud.security.jwt-secret}") String secret, Environment env) {
    return build(secret, env.acceptsProfiles(Profiles.of("dev")));
  }

  static JwtDecoder build(String secret, boolean devProfileActive) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) {
      throw new IllegalStateException("fraud.security.jwt-secret must be at least 32 bytes");
    }
    if (DEV_SECRET.equals(secret) && !devProfileActive) {
      throw new IllegalStateException(
          "The public dev JWT secret is only allowed with the 'dev' profile. Set a private"
              + " FRAUD_JWT_SECRET, or configure spring.security.oauth2.resourceserver.jwt.issuer-uri.");
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
