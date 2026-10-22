package com.acme.fraud.api;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApiConfiguration {

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
}
