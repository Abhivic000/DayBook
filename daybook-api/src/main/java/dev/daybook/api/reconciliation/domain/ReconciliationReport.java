package dev.daybook.api.reconciliation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one reconciliation run (FR-10.3).
 *
 * @param error why the run could not complete, if it failed
 */
public record ReconciliationReport(
    UUID id,
    Trigger trigger,
    Status status,
    Instant startedAt,
    Instant finishedAt,
    int checksRun,
    List<Violation> violations,
    @Nullable String error) {

  /** What started the run. */
  public enum Trigger {
    SCHEDULED,
    ON_DEMAND
  }

  /** Whether every check ran. A FAILED run proves nothing either way. */
  public enum Status {
    COMPLETED,
    FAILED
  }

  public ReconciliationReport {
    violations = List.copyOf(violations);
  }

  public Duration duration() {
    return Duration.between(startedAt, finishedAt);
  }

  public long count(Check check) {
    return violations.stream().filter(v -> v.check() == check).count();
  }

  public boolean clean() {
    return status == Status.COMPLETED && violations.isEmpty();
  }
}
