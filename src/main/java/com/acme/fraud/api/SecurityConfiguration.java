package com.acme.fraud.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server. Roles come from the claim named by {@code fraud.security.roles-claim}
 * (default {@code roles}; a dotted path such as {@code realm_access.roles} is supported) and map to
 * {@code ROLE_*} authorities. Authentication and authorization failures are logged and counted. The
 * {@code JwtDecoder} comes from {@link Hs256JwtDecoderConfiguration} (dev/test shared secret) or,
 * in production, from Spring Boot's issuer-based auto-configuration; the filter chain is the same
 * in both cases.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

  public static final String ANALYST = "FRAUD_ANALYST";
  public static final String ADMIN = "FRAUD_ADMIN";

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      MeterRegistry meters,
      @Value("${fraud.security.roles-claim:roles}") String rolesClaim)
      throws Exception {
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
            oauth ->
                oauth
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(rolesClaim)))
                    .authenticationEntryPoint(SecurityAuditHandlers.entryPoint(meters))
                    .accessDeniedHandler(SecurityAuditHandlers.accessDeniedHandler(meters)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(SecurityAuditHandlers.entryPoint(meters))
                    .accessDeniedHandler(SecurityAuditHandlers.accessDeniedHandler(meters)))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .headers(Customizer.withDefaults());
    return http.build();
  }

  static JwtAuthenticationConverter jwtAuthenticationConverter(String rolesClaim) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RolesClaimConverter(rolesClaim));
    return converter;
  }
}
