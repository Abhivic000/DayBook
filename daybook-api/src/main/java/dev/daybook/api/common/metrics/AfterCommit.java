package dev.daybook.api.common.metrics;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action only once the surrounding database transaction has committed — or immediately if
 * there is none. Used for metrics: counting inside a transaction that later rolls back would report
 * money movements that never happened.
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
