package com.acme.fraud.platform;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes unpublished outbox rows in creation order and marks each as published only after the
 * broker acknowledged it (at-least-once; consumers are idempotent). Stops at the first failure to
 * preserve ordering.
 */
public class OutboxRelay {

  /** Sends one message and returns only once the broker acknowledged it; throws on failure. */
  @FunctionalInterface
  public interface MessagePublisher {
    void publish(String topic, String key, String payload);
  }

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final JdbcClient jdbc;
  private final MessagePublisher publisher;
  private final int batchSize;
  private final TransactionOperations tx;

  public OutboxRelay(JdbcClient jdbc, MessagePublisher publisher, int batchSize) {
    this(jdbc, publisher, batchSize, TransactionOperations.withoutTransaction());
  }

  /**
   * With a real transaction, the batch is locked with FOR UPDATE SKIP LOCKED so several app
   * instances can relay concurrently without publishing the same rows.
   */
  public OutboxRelay(
      JdbcClient jdbc, MessagePublisher publisher, int batchSize, TransactionOperations tx) {
    this.jdbc = jdbc;
    this.publisher = publisher;
    this.batchSize = batchSize;
    this.tx = tx;
  }

  /** Publishes one batch; returns the number of rows published. */
  public int relayBatch() {
    Integer n = tx.execute(status -> doRelayBatch());
    return n == null ? 0 : n;
  }

  private int doRelayBatch() {
    List<Row> rows =
        jdbc.sql(
                "SELECT id, topic, message_key, payload::text AS payload FROM outbox WHERE"
                    + " published_at IS NULL ORDER BY created_at, id LIMIT :n FOR UPDATE SKIP"
                    + " LOCKED")
            .param("n", batchSize)
            .query(
                (rs, i) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload")))
            .list();
    int published = 0;
    for (Row r : rows) {
      try {
        publisher.publish(r.topic(), r.key(), r.payload());
      } catch (RuntimeException e) {
        LOG.warn("Outbox publish failed, will retry: topic={} id={}", r.topic(), r.id(), e);
        break;
      }
      jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id")
          .param("id", r.id())
          .update();
      published++;
    }
    return published;
  }

  private record Row(UUID id, String topic, String key, String payload) {}
}
