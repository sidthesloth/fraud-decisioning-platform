package com.acme.fraud.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.fraud.ingestion.TransactionEventProcessor.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResilientEventHandlerTest {

  private static class Dlq implements DeadLetterSink {
    final List<String> errors = new ArrayList<>();
    boolean fail;

    @Override
    public void send(String raw, String type, String msg, int attempts) {
      if (fail) {
        throw new IllegalStateException("dlq down");
      }
      errors.add(type + ":" + attempts);
    }
  }

  /** Processor stub: throws the queued failures, then succeeds. */
  private static class Stub extends TransactionEventProcessor {
    final List<RuntimeException> failures;
    int calls;

    Stub(List<RuntimeException> failures) {
      super(null, null, null, null, null, null, null);
      this.failures = new ArrayList<>(failures);
    }

    @Override
    public Result process(String raw) {
      calls++;
      if (!failures.isEmpty()) {
        throw failures.remove(0);
      }
      return Result.PROCESSED;
    }
  }

  private static ResilientEventHandler handler(Stub s, Dlq d, List<Duration> sleeps) {
    return new ResilientEventHandler(s, d, 3, Duration.ofMillis(100), sleeps::add);
  }

  @Test
  void poisonGoesStraightToDlqWithoutRetry() {
    Stub s = new Stub(List.of(new InvalidEventException("bad")));
    Dlq d = new Dlq();
    assertThat(handler(s, d, new ArrayList<>()).handle("x")).isEqualTo(Result.DEAD_LETTERED);
    assertThat(s.calls).isEqualTo(1);
    assertThat(d.errors).containsExactly("InvalidEventException:1");
  }

  @Test
  void transientFailureIsRetriedWithBackoffThenSucceeds() {
    Stub s = new Stub(List.of(new IllegalStateException("a"), new IllegalStateException("b")));
    List<Duration> sleeps = new ArrayList<>();
    assertThat(handler(s, new Dlq(), sleeps).handle("x")).isEqualTo(Result.PROCESSED);
    assertThat(s.calls).isEqualTo(3);
    assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
  }

  @Test
  void exhaustedRetriesGoToDlq() {
    Stub s =
        new Stub(
            List.of(
                new IllegalStateException("a"),
                new IllegalStateException("b"),
                new IllegalStateException("c")));
    Dlq d = new Dlq();
    assertThat(handler(s, d, new ArrayList<>()).handle("x")).isEqualTo(Result.DEAD_LETTERED);
    assertThat(d.errors).containsExactly("IllegalStateException:3");
  }

  @Test
  void dlqFailurePropagatesSoMessageIsNotAcked() {
    Dlq d = new Dlq();
    d.fail = true;
    Stub s = new Stub(List.of(new InvalidEventException("bad")));
    assertThatThrownBy(() -> handler(s, d, new ArrayList<>()).handle("x"))
        .isInstanceOf(IllegalStateException.class);
  }
}
