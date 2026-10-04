package dev.daybook.api.idempotency.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired idempotency records (FR-5.7). Safe to run on several instances at once: deleting
 * an already-deleted row is a no-op.
 */
@Component
public class IdempotencyPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

  private final IdempotencyRepository repository;

  IdempotencyPurgeJob(IdempotencyRepository repository) {
    this.repository = repository;
  }

  @Scheduled(
      fixedDelayString = "${daybook.idempotency.purge-interval:PT1H}",
      initialDelayString = "${daybook.idempotency.purge-interval:PT1H}")
  public int purgeExpired() {
    int deleted = repository.deleteExpired();
    if (deleted > 0) {
      log.info("Purged {} expired idempotency keys", deleted);
    }
    return deleted;
  }
}
