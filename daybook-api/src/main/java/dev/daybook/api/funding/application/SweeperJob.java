package dev.daybook.api.funding.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the sweeper on a schedule. {@code daybook.sweeper.enabled=false} switches it off (tests call
 * {@link PendingTransactionSweeper#sweep()} directly instead).
 */
@Component
@ConditionalOnProperty(
    name = "daybook.sweeper.enabled",
    havingValue = "true",
    matchIfMissing = true)
class SweeperJob {

  private final PendingTransactionSweeper sweeper;

  SweeperJob(PendingTransactionSweeper sweeper) {
    this.sweeper = sweeper;
  }

  @Scheduled(
      fixedDelayString = "${daybook.sweeper.interval:PT30S}",
      initialDelayString = "${daybook.sweeper.interval:PT30S}")
  void sweep() {
    sweeper.sweep();
  }
}
