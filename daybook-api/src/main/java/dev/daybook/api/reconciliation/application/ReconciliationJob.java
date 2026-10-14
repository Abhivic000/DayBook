package dev.daybook.api.reconciliation.application;

import dev.daybook.api.reconciliation.domain.ReconciliationReport.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a schedule (FR-10.1) and purges old reports. {@code
 * daybook.reconciliation.enabled=false} switches the schedule off (tests run it explicitly).
 */
@Component
@ConditionalOnProperty(
    name = "daybook.reconciliation.enabled",
    havingValue = "true",
    matchIfMissing = true)
class ReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

  private final ReconciliationService reconciliation;
  private final ReconciliationReports reports;
  private final ReconciliationProperties properties;

  ReconciliationJob(
      ReconciliationService reconciliation,
      ReconciliationReports reports,
      ReconciliationProperties properties) {
    this.reconciliation = reconciliation;
    this.reports = reports;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${daybook.reconciliation.interval:PT5M}",
      initialDelayString = "${daybook.reconciliation.initial-delay:PT1M}")
  void run() {
    try {
      reconciliation.run(Trigger.SCHEDULED);
    } catch (ReconciliationInProgressException e) {
      log.info("Scheduled reconciliation skipped: a run is already in progress");
    }
  }

  @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1H")
  void purge() {
    int deleted = reports.deleteOlderThan(properties.retention());
    if (deleted > 0) {
      log.info("Purged {} old reconciliation reports", deleted);
    }
  }
}
