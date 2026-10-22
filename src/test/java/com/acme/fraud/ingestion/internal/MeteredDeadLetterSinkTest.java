package com.acme.fraud.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MeteredDeadLetterSinkTest {

  @Test
  void countsOnlyAfterDelegateSucceeds() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MeteredDeadLetterSink ok = new MeteredDeadLetterSink((m, t, e, a) -> {}, registry);
    ok.send("x", "InvalidEventException", "bad", 1);
    MeteredDeadLetterSink failing =
        new MeteredDeadLetterSink(
            (m, t, e, a) -> {
              throw new IllegalStateException("db down");
            },
            registry);
    try {
      failing.send("y", "InvalidEventException", "bad", 1);
    } catch (IllegalStateException expected) {
      // not counted
    }
    assertThat(registry.get("fraud.dlq.messages.total").counter().count()).isEqualTo(1.0);
  }
}
