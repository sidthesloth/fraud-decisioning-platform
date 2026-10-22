package com.acme.fraud.ruledomain.internal;

import com.acme.fraud.ruledomain.ConditionParser;
import com.acme.fraud.ruledomain.RuleAction;
import com.acme.fraud.ruledomain.RuleCatalog;
import com.acme.fraud.ruledomain.RuleDraft;
import com.acme.fraud.ruledomain.RuleVersion;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC rule catalog. Versions are insert-only; only {@code enabled} is ever updated. */
public class JdbcRuleCatalog implements RuleCatalog {

  private static final String SELECT =
      "SELECT rule_id, version, name, description, priority, condition::text AS condition, score,"
          + " reason_code, enabled, effective_from, effective_to FROM fraud_rule_versions";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final ConditionParser parser;

  public JdbcRuleCatalog(JdbcClient jdbc, TransactionTemplate tx, ConditionParser parser) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.parser = parser;
  }

  @Override
  public StoredVersion create(String ruleId, RuleDraft draft, String conditionJson) {
    return required(
        tx.execute(
            s -> {
              int inserted =
                  jdbc.sql("INSERT INTO fraud_rules (rule_id) VALUES (:id) ON CONFLICT DO NOTHING")
                      .param("id", ruleId)
                      .update();
              if (inserted == 0) {
                throw new RuleAlreadyExistsException(ruleId);
              }
              return insertVersion(ruleId, 1, draft, conditionJson);
            }));
  }

  @Override
  public StoredVersion addVersion(String ruleId, RuleDraft draft, String conditionJson) {
    return required(
        tx.execute(
            s -> {
              boolean exists =
                  !jdbc.sql("SELECT rule_id FROM fraud_rules WHERE rule_id = :id FOR UPDATE")
                      .param("id", ruleId)
                      .query(String.class)
                      .list()
                      .isEmpty();
              if (!exists) {
                throw new RuleNotFoundException("Rule not found: " + ruleId);
              }
              int next =
                  jdbc.sql(
                          "SELECT COALESCE(MAX(version), 0) + 1 FROM fraud_rule_versions"
                              + " WHERE rule_id = :id")
                      .param("id", ruleId)
                      .query(Integer.class)
                      .single();
              return insertVersion(ruleId, next, draft, conditionJson);
            }));
  }

  private StoredVersion insertVersion(String ruleId, int version, RuleDraft d, String json) {
    jdbc.sql(
            "INSERT INTO fraud_rule_versions (rule_id, version, name, description, priority,"
                + " condition, score, reason_code, enabled, effective_from, effective_to) VALUES"
                + " (:id, :ver, :name, :desc, :prio, :cond::jsonb, :score, :reason, FALSE, :from,"
                + " :to)")
        .param("id", ruleId)
        .param("ver", version)
        .param("name", d.name())
        .param("desc", d.description() == null ? "" : d.description())
        .param("prio", d.priority())
        .param("cond", json)
        .param("score", d.action().scoreContribution())
        .param("reason", d.action().reasonCode())
        .param("from", d.effectiveFrom() == null ? null : Timestamp.from(d.effectiveFrom()))
        .param("to", d.effectiveTo() == null ? null : Timestamp.from(d.effectiveTo()))
        .update();
    return load(ruleId, version);
  }

  @Override
  public StoredVersion setEnabled(String ruleId, int version, boolean enabled) {
    int updated =
        jdbc.sql(
                "UPDATE fraud_rule_versions SET enabled = :e, row_version = row_version + 1"
                    + " WHERE rule_id = :id AND version = :ver")
            .param("e", enabled)
            .param("id", ruleId)
            .param("ver", version)
            .update();
    if (updated == 0) {
      throw new RuleNotFoundException("Rule version not found: " + ruleId + " v" + version);
    }
    return load(ruleId, version);
  }

  @Override
  public List<StoredVersion> latestVersions() {
    return jdbc.sql(
            SELECT
                + " v WHERE version = (SELECT MAX(version) FROM fraud_rule_versions m"
                + " WHERE m.rule_id = v.rule_id) ORDER BY rule_id")
        .query((rs, n) -> map(rs))
        .list();
  }

  @Override
  public List<StoredVersion> versions(String ruleId) {
    return jdbc.sql(SELECT + " WHERE rule_id = :id ORDER BY version")
        .param("id", ruleId)
        .query((rs, n) -> map(rs))
        .list();
  }

  private StoredVersion load(String ruleId, int version) {
    return jdbc.sql(SELECT + " WHERE rule_id = :id AND version = :ver")
        .param("id", ruleId)
        .param("ver", version)
        .query((rs, n) -> map(rs))
        .single();
  }

  private StoredVersion map(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp from = rs.getTimestamp("effective_from");
    Timestamp to = rs.getTimestamp("effective_to");
    String json = rs.getString("condition");
    RuleVersion v =
        new RuleVersion(
            rs.getString("rule_id"),
            rs.getInt("version"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getInt("priority"),
            parser.parse(json),
            new RuleAction(rs.getInt("score"), rs.getString("reason_code")),
            rs.getBoolean("enabled"),
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant());
    return new StoredVersion(v, json);
  }

  private static StoredVersion required(StoredVersion v) {
    if (v == null) {
      throw new IllegalStateException("Transaction returned no result");
    }
    return v;
  }
}
