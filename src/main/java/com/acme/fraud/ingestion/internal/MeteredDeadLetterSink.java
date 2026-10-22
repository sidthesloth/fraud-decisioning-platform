package com.acme.fraud.ingestion.internal;

import com.acme.fraud.ingestion.DeadLetterSink;
import io.micrometer.core.instrument.MeterRegistry;

/** Counts dead-lettered messages ({@code fraud.dlq.messages.total}) once safely written. */
public final class MeteredDeadLetterSink implements DeadLetterSink {

  private final DeadLetterSink delegate;
  private final MeterRegistry registry;

  public MeteredDeadLetterSink(DeadLetterSink delegate, MeterRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  @Override
  public void send(String rawMessage, String errorType, String errorMessage, int attempts) {
    delegate.send(rawMessage, errorType, errorMessage, attempts);
    registry.counter("fraud.dlq.messages.total", "error_type", errorType).increment();
  }
}
