package com.acme.fraud.ingestion;

import com.acme.fraud.evaluation.TransactionFacts;

/** Inbox (idempotency by event id) and transaction history written by ingestion. */
public interface IngestionLog {

  /** Returns true if this event id was not seen before and is now claimed. */
  boolean claim(String eventId);

  void recordHistory(TransactionFacts facts);
}
