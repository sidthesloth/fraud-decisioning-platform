package com.acme.fraud.evaluation.internal;

import com.acme.fraud.evaluation.ContextEnricher;
import com.acme.fraud.evaluation.EnrichedAggregates;
import com.acme.fraud.evaluation.EnrichmentUnavailableException;
import com.acme.fraud.evaluation.TransactionFacts;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL-backed enricher. Aggregates are computed over {@code transaction_history} strictly
 * before the current transaction (the current transaction is excluded by id).
 *
 * <p>TODO(ADAPTER): {@code riskTier} source and {@code geoVelocity} definition are unconfirmed and
 * are left unavailable (null). {@code crossBorder} is defined as country != home country.
 */
public final class JdbcContextEnricher implements ContextEnricher {

  private final JdbcClient jdbc;
  private final String homeCountry;

  public JdbcContextEnricher(JdbcClient jdbc, String homeCountry) {
    this.jdbc = jdbc;
    this.homeCountry = homeCountry;
  }

  @Override
  public EnrichedAggregates enrich(TransactionFacts facts) {
    try {
      Instant at = facts.occurredAt();
      Timestamp hourAgo = Timestamp.from(at.minus(Duration.ofHours(1)));
      Timestamp dayAgo = Timestamp.from(at.minus(Duration.ofHours(24)));
      Timestamp now = Timestamp.from(at);

      Integer lastHourCount =
          jdbc.sql(
                  "SELECT count(*) FROM transaction_history WHERE account_id = :account"
                      + " AND transaction_id <> :txn AND occurred_at >= :from AND occurred_at <= :to")
              .param("account", facts.accountId())
              .param("txn", facts.transactionId())
              .param("from", hourAgo)
              .param("to", now)
              .query(Integer.class)
              .single();

      BigDecimal last24hAmount =
          jdbc.sql(
                  "SELECT COALESCE(sum(amount), 0) FROM transaction_history WHERE account_id ="
                      + " :account AND transaction_id <> :txn AND occurred_at >= :from AND"
                      + " occurred_at <= :to")
              .param("account", facts.accountId())
              .param("txn", facts.transactionId())
              .param("from", dayAgo)
              .param("to", now)
              .query(BigDecimal.class)
              .single();

      Boolean newDevice = null;
      if (facts.deviceId() != null) {
        Boolean seen =
            jdbc.sql(
                    "SELECT EXISTS (SELECT 1 FROM transaction_history WHERE account_id = :account"
                        + " AND device_id = :device AND transaction_id <> :txn AND occurred_at <="
                        + " :to)")
                .param("account", facts.accountId())
                .param("device", facts.deviceId())
                .param("txn", facts.transactionId())
                .param("to", now)
                .query(Boolean.class)
                .single();
        newDevice = !seen;
      }

      Boolean crossBorder = facts.country() == null ? null : !homeCountry.equals(facts.country());
      return new EnrichedAggregates(
          lastHourCount, last24hAmount, newDevice, crossBorder, null, null);
    } catch (DataAccessException e) {
      throw new EnrichmentUnavailableException("Enrichment query failed", e);
    }
  }
}
