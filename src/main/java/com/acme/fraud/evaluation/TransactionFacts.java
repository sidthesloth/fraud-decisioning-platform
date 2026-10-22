package com.acme.fraud.evaluation;

import java.math.BigDecimal;
import java.time.Instant;

/** Raw facts of one categorized transaction, as carried by the inbound event. */
public record TransactionFacts(
    String transactionId,
    String customerId,
    String accountId,
    BigDecimal amount,
    String currency,
    String merchantCategory,
    String merchantId,
    String channel,
    Instant occurredAt,
    String country,
    String city,
    String deviceId) {

  public TransactionFacts {
    require(transactionId, "transactionId");
    require(customerId, "customerId");
    require(accountId, "accountId");
    require(currency, "currency");
    require(channel, "channel");
    if (amount == null || amount.signum() < 0) {
      throw new IllegalArgumentException("amount must be present and non-negative");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt is required");
    }
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
