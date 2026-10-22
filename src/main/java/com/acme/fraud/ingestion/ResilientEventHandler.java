package com.acme.fraud.ingestion;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failure semantics around {@link TransactionEventProcessor}: poison messages go straight to the
 * dead-letter sink; transient failures are retried with exponential backoff and then dead-lettered.
 * Returning normally means the message is settled and may be acknowledged; if the dead-letter write
 * itself fails the exception propagates so the message is NOT acknowledged.
 */
public class ResilientEventHandler {

  /** Pause between attempts; replaceable in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  private static final Logger LOG = LoggerFactory.getLogger(ResilientEventHandler.class);

  private final TransactionEventProcessor processor;
  private final DeadLetterSink dlq;
  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Sleeper sleeper;

  public ResilientEventHandler(
      TransactionEventProcessor processor,
      DeadLetterSink dlq,
      int maxAttempts,
      Duration initialBackoff,
      Sleeper sleeper) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    this.processor = processor;
    this.dlq = dlq;
    this.maxAttempts = maxAttempts;
    this.initialBackoff = initialBackoff;
    this.sleeper = sleeper;
  }

  public TransactionEventProcessor.Result handle(String raw) {
    Duration backoff = initialBackoff;
    for (int attempt = 1; ; attempt++) {
      try {
        return processor.process(raw);
      } catch (InvalidEventException e) {
        LOG.warn("Poison message, dead-lettering: {}", e.getMessage());
        dlq.send(raw, e.getClass().getSimpleName(), e.getMessage(), attempt);
        return TransactionEventProcessor.Result.DEAD_LETTERED;
      } catch (RuntimeException e) {
        if (attempt >= maxAttempts) {
          LOG.error("Retries exhausted after {} attempts, dead-lettering", attempt, e);
          dlq.send(raw, e.getClass().getSimpleName(), String.valueOf(e.getMessage()), attempt);
          return TransactionEventProcessor.Result.DEAD_LETTERED;
        }
        LOG.warn("Attempt {} failed, retrying in {}", attempt, backoff, e);
        pause(backoff);
        backoff = backoff.multipliedBy(2);
      }
    }
  }

  private void pause(Duration d) {
    try {
      sleeper.sleep(d);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while backing off", ie);
    }
  }
}
