package com.acme.fraud;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action only after the surrounding transaction commits, so side effects such as metric
 * increments are not counted for work that is rolled back. Without an active transaction the action
 * runs immediately.
 */
public final class AfterCommit {

  private AfterCommit() {}

  public static void run(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
