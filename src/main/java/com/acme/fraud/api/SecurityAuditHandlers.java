package com.acme.fraud.api;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Audits authentication failures (401) and authorization failures (403) before delegating to the
 * standard bearer-token responses. Logs method, path and reason only: never the token, and the
 * subject only for 403 (where it is already authenticated). Counted in {@code
 * fraud.security.failures.total} (tag {@code type}).
 */
final class SecurityAuditHandlers {

  private static final Logger LOG = LoggerFactory.getLogger("fraud.security.audit");

  private SecurityAuditHandlers() {}

  static AuthenticationEntryPoint entryPoint(MeterRegistry meters) {
    BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    return (HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException ex) -> {
      meters.counter("fraud.security.failures.total", "type", "unauthenticated").increment();
      LOG.warn(
          "Authentication failed: {} {} reason={}",
          request.getMethod(),
          request.getRequestURI(),
          ex.getClass().getSimpleName());
      delegate.commence(request, response, ex);
    };
  }

  static AccessDeniedHandler accessDeniedHandler(MeterRegistry meters) {
    BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();
    return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) -> {
      meters.counter("fraud.security.failures.total", "type", "forbidden").increment();
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      LOG.warn(
          "Access denied: {} {} subject={}",
          request.getMethod(),
          request.getRequestURI(),
          auth == null ? "unknown" : auth.getName());
      delegate.handle(request, response, ex);
    };
  }
}
