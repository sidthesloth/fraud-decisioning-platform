package com.acme.fraud.ingestion;

/**
 * The inbound message is not a valid categorized-transaction CloudEvent. Never retried: goes to
 * DLT.
 */
public class InvalidEventException extends RuntimeException {

  public InvalidEventException(String message) {
    super(message);
  }

  public InvalidEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
