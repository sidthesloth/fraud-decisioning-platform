package com.acme.fraud.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server. Roles come from the {@code roles} claim (e.g. {@code FRAUD_ANALYST}) and map
 * to {@code ROLE_*} authorities. The {@code JwtDecoder} comes from {@link
 * Hs256JwtDecoderConfiguration} (dev/test shared secret) or, in production, from Spring Boot's
 * issuer-based auto-configuration; the filter chain is the same in both cases.
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
}
