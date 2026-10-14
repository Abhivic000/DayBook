package dev.daybook.api.reconciliation.application;

import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import dev.daybook.api.reconciliation.domain.ReconciliationReport.Status;
import dev.daybook.api.reconciliation.domain.ReconciliationReport.Trigger;
import dev.daybook.api.reconciliation.domain.Violation;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the system's central claim (FR-10, ADR 0018): re-adds the books from scratch and reports
 * anything that does not add up.
 *
 * <p><strong>Read-only, always.</strong> It never repairs: a "fixed" balance would hide the bug
 * that broke it. All checks run in one {@code REPEATABLE READ}, read-only database transaction, so
 * they see one consistent instant of the database while transfers carry on unblocked.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  private final ReconciliationChecks checks;
  private final ReconciliationReports reports;
  private final ReconciliationMetrics metrics;
  private final ReconciliationProperties properties;
  private final Clock clock;
  private final TransactionTemplate snapshot;

  ReconciliationService(
      ReconciliationChecks checks,
      ReconciliationReports reports,
      ReconciliationMetrics metrics,
      ReconciliationProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.checks = checks;
    this.reports = reports;
    this.metrics = metrics;
    this.properties = properties;
    this.clock = clock;
    this.snapshot = new TransactionTemplate(transactionManager);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshot.setReadOnly(true);
  }

  /**
   * Runs every check and persists the report.
   *
   * @throws ReconciliationInProgressException another run is in progress
   */
  public ReconciliationReport run(Trigger trigger) {
    UUID id = UUID.randomUUID();
    Instant started = clock.instant();
    ReconciliationReport report;
    try {
      List<Violation> violations = snapshot.execute(status -> runChecks());
      report =
          new ReconciliationReport(
              id,
              trigger,
              Status.COMPLETED,
              started,
              clock.instant(),
              Check.values().length,
              violations,
              null);
    } catch (ReconciliationInProgressException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Reconciliation run {} failed", id, e);
      report =
          new ReconciliationReport(
              id, trigger, Status.FAILED, started, clock.instant(), 0, List.of(), e.toString());
    }
    reports.save(report);
    metrics.record(report);
    logOutcome(report);
    return report;
  }

  private List<Violation> runChecks() {
    if (!checks.tryLockRun()) {
      throw new ReconciliationInProgressException();
    }
    checks.limitStatementTime(properties.statementTimeout());
    boolean projectionAvailable = checks.projectionAvailable();
    List<Violation> violations = new ArrayList<>();
    for (Check check : Check.values()) {
      boolean needsProjection =
          check == Check.PROJECTION_MISMATCH || check == Check.PROJECTION_STALE;
      if (check == Check.PROJECTION_UNAVAILABLE) {
        if (!projectionAvailable) {
          violations.add(
              new Violation(
                  check, null, null, "Projection tables not found; projection checks skipped"));
        }
      } else if (!needsProjection || projectionAvailable) {
        violations.addAll(checks.find(check, properties, properties.maxViolationsPerCheck()));
      }
    }
    return violations;
  }

  private static void logOutcome(ReconciliationReport report) {
    long critical =
        report.violations().stream().filter(v -> v.severity() == Check.Severity.CRITICAL).count();
    if (report.status() == Status.FAILED) {
      return; // already logged with its cause
    }
    if (critical > 0) {
      log.error(
          "Reconciliation {}: {} CRITICAL violation(s) — money may be wrong. Report {}",
          report.trigger(),
          critical,
          report.id());
    } else if (!report.violations().isEmpty()) {
      log.warn(
          "Reconciliation {}: {} warning(s). Report {}",
          report.trigger(),
          report.violations().size(),
          report.id());
    } else {
      log.info("Reconciliation {}: clean in {}", report.trigger(), report.duration());
    }
  }
}
