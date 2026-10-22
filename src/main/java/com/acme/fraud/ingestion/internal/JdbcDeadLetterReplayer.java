package com.acme.fraud.ingestion.internal;

import com.acme.fraud.ingestion.DeadLetterReplayer;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Copies the original message of each dead letter into a new outbox row for the input topic and
 * marks the dead letter as replayed (in headers), in one transaction. The consumer is idempotent by
 * event id, so replaying an already-processed event is harmless.
 */
public class JdbcDeadLetterReplayer implements DeadLetterReplayer {

  private record Letter(UUID id, String original) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;

  public JdbcDeadLetterReplayer(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
  }

  @Override
  public ReplayResult replay(List<UUID> ids, int limit) {
    return tx.execute(status -> doReplay(ids, limit));
  }

  private ReplayResult doReplay(List<UUID> ids, int limit) {
    String base =
        "SELECT id, payload->>'originalMessage' AS original FROM outbox WHERE topic = :topic"
            + " AND NOT jsonb_exists(headers, 'replayedAt')";
    List<Letter> letters =
        (ids.isEmpty()
                ? jdbc.sql(base + " ORDER BY created_at, id LIMIT :n FOR UPDATE").param("n", limit)
                : jdbc.sql(base + " AND id IN (:ids) ORDER BY created_at, id FOR UPDATE")
                    .param("ids", ids))
            .param("topic", JdbcDeadLetterSink.DLQ_TOPIC)
            .query((rs, i) -> new Letter(rs.getObject("id", UUID.class), rs.getString("original")))
            .list();
    int replayed = 0;
    int skipped = ids.isEmpty() ? 0 : ids.size() - letters.size();
    for (Letter l : letters) {
      JsonNode node = parse(l.original());
      if (node == null) {
        skipped++;
        continue;
      }
      String key = node.hasNonNull("id") ? node.get("id").asString() : l.id().toString();
      jdbc.sql(
              "INSERT INTO outbox (id, topic, message_key, payload, headers) VALUES (:id, :topic,"
                  + " :key, :payload::jsonb, jsonb_build_object('replayOf', :src::text))")
          .param("id", UUID.randomUUID())
          .param("topic", TransactionEventListener.INPUT_TOPIC)
          .param("key", key)
          .param("payload", node.toString())
          .param("src", l.id().toString())
          .update();
      jdbc.sql(
              "UPDATE outbox SET headers = headers || jsonb_build_object('replayedAt', now()::text)"
                  + " WHERE id = :id")
          .param("id", l.id())
          .update();
      replayed++;
    }
    return new ReplayResult(replayed, skipped);
  }

  private JsonNode parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      JsonNode n = json.readTree(raw);
      return n != null && n.isObject() ? n : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
