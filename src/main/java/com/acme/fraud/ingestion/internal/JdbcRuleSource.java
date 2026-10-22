package com.acme.fraud.ingestion.internal;

import com.acme.fraud.ingestion.RuleSource;
import com.acme.fraud.ruledomain.ConditionParser;
import com.acme.fraud.ruledomain.RuleAction;
import com.acme.fraud.ruledomain.RuleVersion;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Loads enabled rule versions from {@code fraud_rule_versions}; the engine applies windows. */
public class JdbcRuleSource implements RuleSource {

  private final JdbcClient jdbc;
  private final ConditionParser parser;

  public JdbcRuleSource(JdbcClient jdbc, ConditionParser parser) {
    this.jdbc = jdbc;
    this.parser = parser;
  }

  @Override
  public List<RuleVersion> rules() {
    return jdbc.sql(
            "SELECT rule_id, version, name, description, priority, condition::text AS condition,"
                + " score, reason_code, enabled, effective_from, effective_to FROM"
                + " fraud_rule_versions WHERE enabled ORDER BY priority, rule_id, version")
        .query(
            (rs, n) -> {
              Timestamp from = rs.getTimestamp("effective_from");
              Timestamp to = rs.getTimestamp("effective_to");
              return new RuleVersion(
                  rs.getString("rule_id"),
                  rs.getInt("version"),
                  rs.getString("name"),
                  rs.getString("description"),
                  rs.getInt("priority"),
                  parser.parse(rs.getString("condition")),
                  new RuleAction(rs.getInt("score"), rs.getString("reason_code")),
                  rs.getBoolean("enabled"),
                  from == null ? null : from.toInstant(),
                  to == null ? null : to.toInstant());
            })
        .list();
  }
}
