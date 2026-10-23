package com.acme.fraud.ingestion;

import com.acme.fraud.alert.AlertStore;
import com.acme.fraud.decisions.DecisionStore;
import com.acme.fraud.decisions.DecisionStore.SaveResult;
import com.acme.fraud.evaluation.Decision;
import com.acme.fraud.evaluation.FraudEvaluator;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes one inbound event atomically: claim in inbox, evaluate, persist decision (+outbox),
 * create alert if flagged (+outbox), record history. Any failure rolls back the claim so the
 * message can be retried; a duplicate event id is a no-op.
 */
public class TransactionEventProcessor {

  public enum Result {
    PROCESSED,
    DUPLICATE,
    DEAD_LETTERED
  }

  private final CloudEventParser parser;
  private final IngestionLog log;
  private final RuleSource rules;
  private final FraudEvaluator evaluator;
  private final DecisionStore decisions;
  private final AlertStore alerts;
  private final TransactionTemplate tx;

  public TransactionEventProcessor(
      CloudEventParser parser,
      IngestionLog log,
      RuleSource rules,
      FraudEvaluator evaluator,
      DecisionStore decisions,
      AlertStore alerts,
      TransactionTemplate tx) {
    this.parser = parser;
    this.log = log;
    this.rules = rules;
    this.evaluator = evaluator;
    this.decisions = decisions;
    this.alerts = alerts;
    this.tx = tx;
  }

  /**
   * @throws InvalidEventException for poison messages (route to DLT, do not retry)
   */
  public Result process(String raw) {
    ParsedEvent event = parser.parse(raw);
    long start = System.nanoTime();
    MDC.put("transactionId", event.facts().transactionId());
    MDC.put("customerId", event.facts().customerId());
    try {
      return processParsed(event, start);
    } finally {
      MDC.remove("transactionId");
      MDC.remove("customerId");
      MDC.remove("outcome");
      MDC.remove("latencyMs");
    }
  }

  private Result processParsed(ParsedEvent event, long start) {
    Result result =
        tx.execute(
            status -> {
              if (!log.claim(event.eventId())) {
                return Result.DUPLICATE;
              }
              Decision decision =
                  evaluator.evaluate(event.facts(), rules.rules(), event.inputHash());
              MDC.put("outcome", decision.outcome().name());
              SaveResult saved = decisions.save(decision);
              if (decision.flagged()) {
                alerts.createFor(saved.decisionId(), decision);
              }
              log.recordHistory(event.facts());
              MDC.put("latencyMs", String.valueOf((System.nanoTime() - start) / 1_000_000));
              return Result.PROCESSED;
            });
    if (result == null) {
      throw new IllegalStateException("Transaction returned no result");
    }
    return result;
  }
}
