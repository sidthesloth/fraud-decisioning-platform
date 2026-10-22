package com.acme.fraud.alert.internal;

import com.acme.fraud.alert.AlertStatus;
import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.alert.Severity;
import com.acme.fraud.evaluation.Decision;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC alert store: explicit SQL, optimistic locking via row_version, audit in alert_transitions.
 */
public class JdbcAlertStore implements AlertStore {

  public static final String ALERTS_TOPIC = "fraud.alerts.v1";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;
  private final Clock clock;

  public JdbcAlertStore(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public CreateResult createFor(UUID decisionId, Decision d) {
    if (!d.flagged()) {
      throw new IllegalArgumentException("Alerts are only created for REVIEW/DECLINE decisions");
    }
    CreateResult r = tx.execute(status -> insert(decisionId, d));
    if (r == null) {
      throw new IllegalStateException("Transaction returned no result");
    }
    return r;
  }

  private CreateResult insert(UUID decisionId, Decision d) {
    UUID id = UUID.randomUUID();
    Severity severity = Severity.of(d.outcome(), d.score());
    Timestamp now = Timestamp.from(clock.instant());
    int inserted =
        jdbc.sql(
                "INSERT INTO alerts (alert_id, decision_id, severity, status, created_at,"
                    + " updated_at) VALUES (:id, :dec, :sev, 'OPEN', :now, :now)"
                    + " ON CONFLICT (decision_id) DO NOTHING")
            .param("id", id)
            .param("dec", decisionId)
            .param("sev", severity.name())
            .param("now", now)
            .update();
    if (inserted == 0) {
      UUID existing =
          jdbc.sql("SELECT alert_id FROM alerts WHERE decision_id = :dec")
              .param("dec", decisionId)
              .query(UUID.class)
              .single();
      return new CreateResult(existing, false);
    }
    jdbc.sql(
            "INSERT INTO alert_transitions (alert_id, from_status, to_status, actor, note, at)"
                + " VALUES (:id, NULL, 'OPEN', 'system', 'created', :now)")
        .param("id", id)
        .param("now", now)
        .update();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("alertId", id.toString());
    p.put("decisionId", decisionId.toString());
    p.put("transactionId", d.transactionId());
    p.put("customerId", d.customerId());
    p.put("outcome", d.outcome().name());
    p.put("score", d.score());
    p.put("severity", severity.name());
    jdbc.sql(
            "INSERT INTO outbox (id, topic, message_key, payload) VALUES (:id, :topic, :key,"
                + " :payload::jsonb)")
        .param("id", UUID.randomUUID())
        .param("topic", ALERTS_TOPIC)
        .param("key", d.transactionId())
        .param("payload", json.writeValueAsString(p))
        .update();
    return new CreateResult(id, true);
  }

  @Override
  public Optional<Alert> findById(UUID alertId) {
    return jdbc.sql(
            "SELECT alert_id, decision_id, severity, status, created_at, updated_at, row_version"
                + " FROM alerts WHERE alert_id = :id")
        .param("id", alertId)
        .query(
            (rs, n) ->
                new Alert(
                    rs.getObject("alert_id", UUID.class),
                    rs.getObject("decision_id", UUID.class),
                    Severity.valueOf(rs.getString("severity")),
                    AlertStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("row_version")))
        .optional();
  }

  @Override
  public Alert transition(
      UUID alertId, AlertStatus to, String actor, String note, long expectedVersion) {
    Alert result =
        tx.execute(
            status -> {
              Alert current =
                  findById(alertId).orElseThrow(() -> new AlertNotFoundException(alertId));
              if (current.version() != expectedVersion) {
                throw new StaleAlertException(alertId);
              }
              if (!current.status().canTransitionTo(to)) {
                throw new IllegalTransitionException(current.status(), to);
              }
              Instant now = clock.instant();
              int updated =
                  jdbc.sql(
                          "UPDATE alerts SET status = :to, updated_at = :now, row_version ="
                              + " row_version + 1 WHERE alert_id = :id AND row_version = :v")
                      .param("to", to.name())
                      .param("now", Timestamp.from(now))
                      .param("id", alertId)
                      .param("v", expectedVersion)
                      .update();
              if (updated == 0) {
                throw new StaleAlertException(alertId);
              }
              jdbc.sql(
                      "INSERT INTO alert_transitions (alert_id, from_status, to_status, actor,"
                          + " note, at) VALUES (:id, :from, :to, :actor, :note, :now)")
                  .param("id", alertId)
                  .param("from", current.status().name())
                  .param("to", to.name())
                  .param("actor", actor)
                  .param("note", note)
                  .param("now", Timestamp.from(now))
                  .update();
              return findById(alertId).orElseThrow();
            });
    if (result == null) {
      throw new IllegalStateException("Transaction returned no result");
    }
    return result;
  }
}
