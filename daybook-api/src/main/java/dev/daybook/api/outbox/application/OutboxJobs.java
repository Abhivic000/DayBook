package dev.daybook.api.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules the outbox relay and purge. {@code daybook.outbox.relay-enabled=false} switches the
 * relay off — used by the crash test to commit events that are not yet published.
 */
@Component
@ConditionalOnProperty(
    name = "daybook.outbox.relay-enabled",
    havingValue = "true",
    matchIfMissing = true)
class OutboxJobs {

  private static final Logger log = LoggerFactory.getLogger(OutboxJobs.class);

  private final OutboxRelay relay;
  private final OutboxRepository outbox;
  private final OutboxProperties properties;

  OutboxJobs(OutboxRelay relay, OutboxRepository outbox, OutboxProperties properties) {
    this.relay = relay;
    this.outbox = outbox;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${daybook.outbox.poll-interval:PT0.2S}")
  void relay() {
    relay.relayOnce();
  }

  @Scheduled(
      fixedDelayString = "${daybook.outbox.purge-interval:PT1H}",
      initialDelayString = "${daybook.outbox.purge-interval:PT1H}")
  void purge() {
    int deleted = outbox.deletePublishedOlderThan(properties.retention());
    if (deleted > 0) {
      log.info("Purged {} published outbox messages", deleted);
    }
  }
}
