package com.acme.fraud.api;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApiConfiguration {

  @Bean
  OpenAPI fraudOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Fraud Decisioning Platform API")
                .version("v1")
                .description(
                    "Decisions, alerts, rules, DLQ replay. JWT bearer; roles in 'roles' claim."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }

  @Bean
  RateLimiter ruleAdminRateLimiter(
      @Value("${fraud.api.rule-admin-limit-per-second:5}") int perSecond) {
    return RateLimiter.of(
        "rule-admin",
        RateLimiterConfig.custom()
            .limitForPeriod(perSecond)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)
            .build());
  }

  @Bean
  RateLimiter dlqReplayRateLimiter(
      @Value("${fraud.api.dlq-replay-limit-per-second:1}") int perSecond) {
    return RateLimiter.of(
        "dlq-replay",
        RateLimiterConfig.custom()
            .limitForPeriod(perSecond)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)
            .build());
  }
}
