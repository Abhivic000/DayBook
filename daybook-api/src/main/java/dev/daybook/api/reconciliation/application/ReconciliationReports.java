package dev.daybook.api.reconciliation.application;

import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Persisted run reports (FR-10.3). */
public interface ReconciliationReports {

  /** A run without its violation details, for listings. */
  record RunSummary(
      UUID id,
      ReconciliationReport.Trigger trigger,
      ReconciliationReport.Status status,
      Instant startedAt,
      Instant finishedAt,
      int checksRun,
      int violationCount,
      @Nullable String error) {}

  void save(ReconciliationReport report);

  /** Newest first. */
  List<RunSummary> recent(int limit);

  /** One run with all its violations. */
  Optional<ReconciliationReport> find(UUID runId);

  int deleteOlderThan(Duration age);
}
