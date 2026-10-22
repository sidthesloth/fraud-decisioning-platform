package com.acme.fraud.evaluation;

/** Signals that enrichment could not be performed; evaluation continues in degraded mode. */
public class EnrichmentUnavailableException extends RuntimeException {

  public EnrichmentUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
