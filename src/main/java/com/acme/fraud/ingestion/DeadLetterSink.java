package com.acme.fraud.ingestion;

/** Port for parking messages that cannot be processed (fraud.dlq.v1). */
public interface DeadLetterSink {

  /** Persists the poison message with error metadata; must not lose the message. */
  void send(String rawMessage, String errorType, String errorMessage, int attempts);
}
