package com.acme.fraud.ingestion.internal;

import com.acme.fraud.evaluation.TransactionFacts;
import com.acme.fraud.ingestion.IngestionLog;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcIngestionLog implements IngestionLog {

  private final JdbcClient jdbc;

  public JdbcIngestionLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean claim(String eventId) {
    return jdbc.sql("INSERT INTO processed_events (event_id) VALUES (:id) ON CONFLICT DO NOTHING")
            .param("id", eventId)
            .update()
        == 1;
  }

  @Override
  public void recordHistory(TransactionFacts f) {
    jdbc.sql(
            "INSERT INTO transaction_history (transaction_id, customer_id, account_id, amount,"
                + " currency, device_id, country, occurred_at) VALUES (:txn, :cust, :acc, :amt,"
                + " :cur, :dev, :country, :at) ON CONFLICT (transaction_id) DO NOTHING")
        .param("txn", f.transactionId())
        .param("cust", f.customerId())
        .param("acc", f.accountId())
        .param("amt", f.amount())
        .param("cur", f.currency())
        .param("dev", f.deviceId())
        .param("country", f.country())
        .param("at", Timestamp.from(f.occurredAt()))
        .update();
  }
}
