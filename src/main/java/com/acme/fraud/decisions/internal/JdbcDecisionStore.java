package com.acme.fraud.decisions.internal;

import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.MatchedRule;
import com.acme.fraud.evaluation.Outcome;
import com.acme.fraud.evaluation.SkippedRule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC-backed decision store. Explicit SQL: the decision, its immutable rule snapshots and the
 * outbox row are written in one transaction (no dual write).
 */
public class JdbcDecisionStore implements DecisionStore {

  public static final String EVALUATIONS_TOPIC = "fraud.evaluations.v1";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;

  public JdbcDecisionStore(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
  }

  @Override
  public SaveResult save(Decision d) {
    SaveResult result = tx.execute(status -> insert(d));
    if (result == null) {
      throw new IllegalStateException("Transaction returned no result");
    }
    return result;
  }

  private SaveResult insert(Decision d) {
    UUID id = UUID.randomUUID();
    int inserted =
        jdbc.sql(
                "INSERT INTO decisions (decision_id, transaction_id, customer_id, outcome, score,"
                    + " enrichment_degraded, input_hash, engine_version, evaluated_at) VALUES"
                    + " (:id, :txn, :cust, :outcome, :score, :degraded, :hash, :engine, :at)"
                    + " ON CONFLICT (transaction_id) DO NOTHING")
            .param("id", id)
            .param("txn", d.transactionId())
            .param("cust", d.customerId())
            .param("outcome", d.outcome().name())
            .param("score", d.score())
            .param("degraded", d.enrichmentDegraded())
            .param("hash", d.inputHash())
            .param("engine", d.engineVersion())
            .param("at", Timestamp.from(d.evaluatedAt()))
            .update();
    if (inserted == 0) {
      UUID existing =
          jdbc.sql("SELECT decision_id FROM decisions WHERE transaction_id = :txn")
              .param("txn", d.transactionId())
              .query(UUID.class)
              .single();
      return new SaveResult(existing, false);
    }
    for (MatchedRule m : d.matchedRules()) {
      jdbc.sql(
              "INSERT INTO decision_matched_rules (decision_id, rule_id, rule_version,"
                  + " contribution, reason_code) VALUES (:id, :rule, :ver, :c, :reason)")
          .param("id", id)
          .param("rule", m.ruleId())
          .param("ver", m.version())
          .param("c", m.contribution())
          .param("reason", m.reasonCode())
          .update();
    }
    for (SkippedRule s : d.skippedRules()) {
      jdbc.sql(
              "INSERT INTO decision_skipped_rules (decision_id, rule_id, rule_version, reason)"
                  + " VALUES (:id, :rule, :ver, :reason)")
          .param("id", id)
          .param("rule", s.ruleId())
          .param("ver", s.version())
          .param("reason", s.reason().name())
          .update();
    }
    jdbc.sql(
            "INSERT INTO outbox (id, topic, message_key, payload) VALUES (:id, :topic, :key,"
                + " :payload::jsonb)")
        .param("id", UUID.randomUUID())
        .param("topic", EVALUATIONS_TOPIC)
        .param("key", d.transactionId())
        .param("payload", json.writeValueAsString(payload(id, d)))
        .update();
    return new SaveResult(id, true);
  }

  private static Map<String, Object> payload(UUID id, Decision d) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("decisionId", id.toString());
    p.put("transactionId", d.transactionId());
    p.put("customerId", d.customerId());
    p.put("outcome", d.outcome().name());
    p.put("score", d.score());
    p.put("enrichmentDegraded", d.enrichmentDegraded());
    p.put("inputHash", d.inputHash());
    p.put("engineVersion", d.engineVersion());
    p.put("evaluatedAt", d.evaluatedAt().toString());
    p.put("matchedRules", d.matchedRules());
    p.put("skippedRules", d.skippedRules());
    return p;
  }

  @Override
  public Optional<StoredDecision> findById(UUID decisionId) {
    return load("decision_id = :v", decisionId);
  }

  @Override
  public Optional<StoredDecision> findByTransactionId(String transactionId) {
    return load("transaction_id = :v", transactionId);
  }

  @Override
  public Optional<Boolean> evaluationPublished(UUID decisionId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM outbox o WHERE o.topic = :topic AND o.message_key ="
                + " d.transaction_id AND o.published_at IS NOT NULL) FROM decisions d"
                + " WHERE d.decision_id = :id")
        .param("topic", EVALUATIONS_TOPIC)
        .param("id", decisionId)
        .query(Boolean.class)
        .optional();
  }

  @Override
  public Map<Outcome, Long> countByOutcome(Instant since) {
    Map<Outcome, Long> counts = new java.util.EnumMap<>(Outcome.class);
    for (Outcome o : Outcome.values()) {
      counts.put(o, 0L);
    }
    jdbc.sql(
            "SELECT outcome, count(*) AS c FROM decisions WHERE evaluated_at >= :since"
                + " GROUP BY outcome")
        .param("since", Timestamp.from(since))
        .query(
            (rs, n) -> {
              counts.put(Outcome.valueOf(rs.getString("outcome")), rs.getLong("c"));
              return null;
            })
        .list();
    return counts;
  }

  @Override
  public DecisionPage search(DecisionQuery q) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    Map<String, Object> params = new LinkedHashMap<>();
    if (q.customerId() != null) {
      where.append(" AND customer_id = :cust");
      params.put("cust", q.customerId());
    }
    if (q.outcome() != null) {
      where.append(" AND outcome = :outcome");
      params.put("outcome", q.outcome().name());
    }
    if (q.from() != null) {
      where.append(" AND evaluated_at >= :from");
      params.put("from", Timestamp.from(q.from()));
    }
    if (q.to() != null) {
      where.append(" AND evaluated_at < :to");
      params.put("to", Timestamp.from(q.to()));
    }
    long total =
        jdbc.sql("SELECT count(*) FROM decisions" + where)
            .params(params)
            .query(Long.class)
            .single();
    List<DecisionSummary> items =
        jdbc.sql(
                "SELECT decision_id, transaction_id, customer_id, outcome, score,"
                    + " enrichment_degraded, evaluated_at FROM decisions"
                    + where
                    + " ORDER BY evaluated_at DESC, decision_id LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", q.size())
            .param("offset", (long) q.page() * q.size())
            .query(
                (rs, n) ->
                    new DecisionSummary(
                        rs.getObject("decision_id", UUID.class),
                        rs.getString("transaction_id"),
                        rs.getString("customer_id"),
                        Outcome.valueOf(rs.getString("outcome")),
                        rs.getInt("score"),
                        rs.getBoolean("enrichment_degraded"),
                        rs.getTimestamp("evaluated_at").toInstant()))
            .list();
    return new DecisionPage(items, total);
  }

  private Optional<StoredDecision> load(String where, Object value) {
    List<Row> rows =
        jdbc.sql(
                "SELECT decision_id, transaction_id, customer_id, outcome, score,"
                    + " enrichment_degraded, input_hash, engine_version, evaluated_at FROM"
                    + " decisions WHERE "
                    + where)
            .param("v", value)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("decision_id", UUID.class),
                        rs.getString("transaction_id"),
                        rs.getString("customer_id"),
                        Outcome.valueOf(rs.getString("outcome")),
                        rs.getInt("score"),
                        rs.getBoolean("enrichment_degraded"),
                        rs.getString("input_hash"),
                        rs.getString("engine_version"),
                        rs.getTimestamp("evaluated_at").toInstant()))
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Row r = rows.get(0);
    List<MatchedRule> matched =
        jdbc.sql(
                "SELECT rule_id, rule_version, contribution, reason_code FROM"
                    + " decision_matched_rules WHERE decision_id = :id ORDER BY rule_id,"
                    + " rule_version")
            .param("id", r.id())
            .query(
                (rs, n) ->
                    new MatchedRule(
                        rs.getString("rule_id"),
                        rs.getInt("rule_version"),
                        rs.getInt("contribution"),
                        rs.getString("reason_code")))
            .list();
    List<SkippedRule> skipped =
        jdbc.sql(
                "SELECT rule_id, rule_version, reason FROM decision_skipped_rules WHERE"
                    + " decision_id = :id ORDER BY rule_id, rule_version")
            .param("id", r.id())
            .query(
                (rs, n) ->
                    new SkippedRule(
                        rs.getString("rule_id"),
                        rs.getInt("rule_version"),
                        SkippedRule.Reason.valueOf(rs.getString("reason"))))
            .list();
    Decision d =
        new Decision(
            r.transactionId(),
            r.customerId(),
            r.outcome(),
            r.score(),
            matched,
            skipped,
            r.degraded(),
            r.hash(),
            r.at(),
            r.engine());
    return Optional.of(new StoredDecision(r.id(), d));
  }

  private record Row(
      UUID id,
      String transactionId,
      String customerId,
      Outcome outcome,
      int score,
      boolean degraded,
      String hash,
      String engine,
      java.time.Instant at) {}
}
