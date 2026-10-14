package dev.daybook.api.reconciliation.application;

import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.Violation;
import java.time.Duration;
import java.util.List;

/**
 * The invariant checks, as queries. Every method must be called inside the run's single read-only
 * snapshot transaction, so all checks see the same instant of the database.
 */
public interface ReconciliationChecks {

  /** Claims the single run slot for this transaction; false if another run holds it. */
  boolean tryLockRun();

  /** Caps each query's run time for this transaction. */
  void limitStatementTime(Duration timeout);

  /** Whether the consumer's projection tables exist (the consumer may never have run). */
  boolean projectionAvailable();

  /** Violations of one check, at most {@code limit} of them. */
  List<Violation> find(Check check, ReconciliationProperties thresholds, int limit);
}
