package dev.daybook.api.reconciliation.web;

import dev.daybook.api.common.domain.NotFoundException;
import dev.daybook.api.reconciliation.application.ReconciliationReports;
import dev.daybook.api.reconciliation.application.ReconciliationReports.RunSummary;
import dev.daybook.api.reconciliation.application.ReconciliationService;
import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import dev.daybook.api.web.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation on demand, and its reports (FR-10.1, FR-10.3). Admin key required. */
@RestController
class ReconciliationAdminController {

  static final int MAX_LIMIT = 100;

  private final ReconciliationService reconciliation;
  private final ReconciliationReports reports;

  ReconciliationAdminController(
      ReconciliationService reconciliation, ReconciliationReports reports) {
    this.reconciliation = reconciliation;
    this.reports = reports;
  }

  /** Runs every check now. 409 if a run is already in progress. */
  @PostMapping("/v1/admin/reconciliation/runs")
  @ResponseStatus(HttpStatus.CREATED)
  ReconciliationReport run() {
    return reconciliation.run(ReconciliationReport.Trigger.ON_DEMAND);
  }

  /** Recent runs, newest first, without violation details. */
  @GetMapping("/v1/admin/reconciliation/runs")
  List<RunSummary> recent(@RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException(
          "invalid-parameter", "limit must be between 1 and " + MAX_LIMIT);
    }
    return reports.recent(limit);
  }

  /** One run with every violation. */
  @GetMapping("/v1/admin/reconciliation/runs/{runId}")
  ReconciliationReport run(@PathVariable UUID runId) {
    return reports.find(runId).orElseThrow(() -> new RunNotFoundException(runId));
  }

  static final class RunNotFoundException extends NotFoundException {
    RunNotFoundException(UUID runId) {
      super("Reconciliation run %s not found".formatted(runId));
    }
  }
}
