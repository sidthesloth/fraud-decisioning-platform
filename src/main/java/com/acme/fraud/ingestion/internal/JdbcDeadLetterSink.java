package com.acme.fraud.ingestion.internal;

import com.acme.fraud.ingestion.DeadLetterSink;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Writes dead letters to the outbox so they are published to fraud.dlq.v1 by the relay. */
public class JdbcDeadLetterSink implements DeadLetterSink {

  public static final String DLQ_TOPIC = "fraud.dlq.v1";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public JdbcDeadLetterSink(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Override
  public void send(String rawMessage, String errorType, String errorMessage, int attempts) {
    UUID id = UUID.randomUUID();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("originalMessage", rawMessage);
    p.put("errorType", errorType);
    p.put("errorMessage", errorMessage);
    p.put("attempts", attempts);
    p.put("failedAt", Instant.now().toString());
    jdbc.sql(
            "INSERT INTO outbox (id, topic, message_key, payload) VALUES (:id, :topic, :key,"
                + " :payload::jsonb)")
        .param("id", id)
        .param("topic", DLQ_TOPIC)
        .param("key", id.toString())
        .param("payload", json.writeValueAsString(p))
        .update();
  }
}
