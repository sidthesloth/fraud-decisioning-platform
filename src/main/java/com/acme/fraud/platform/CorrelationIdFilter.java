package com.acme.fraud.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts the W3C {@code traceparent} trace/span ids into the MDC for the request's duration. */
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("traceparent");
    boolean set = false;
    if (header != null) {
      String[] parts = header.split("-");
      if (parts.length >= 3) {
        MDC.put("traceId", parts[1]);
        MDC.put("spanId", parts[2]);
        set = true;
      }
    }
    try {
      chain.doFilter(request, response);
    } finally {
      if (set) {
        MDC.remove("traceId");
        MDC.remove("spanId");
      }
    }
  }
}
