package dev.daybook.api.reconciliation.domain;

/**
 * Every invariant reconciliation verifies (FR-10.2, ADR 0018). CRITICAL means money is wrong;
 * WARNING means something is stuck, not wrong.
 */
public enum Check {
  /** INV-2: a tenant's ledger entries do not net to zero — money created or destroyed. */
  LEDGER_UNBALANCED(Severity.CRITICAL),
  /** INV-1: a transaction's debits differ from its credits. */
  TRANSACTION_UNBALANCED(Severity.CRITICAL),
  /** INV-3: an account's cached balance differs from the sum of its entries. */
  BALANCE_DRIFT(Severity.CRITICAL),
  /** An account's version differs from its number of entries: a write was lost or doubled. */
  VERSION_DRIFT(Severity.CRITICAL),
  /** INV-6: a USER or WITHDRAWAL_IN_TRANSIT account is negative. */
  NEGATIVE_BALANCE(Severity.CRITICAL),
  /** ADR 0006: in-transit balance differs from the total of PENDING withdrawals. */
  HOLD_MISMATCH(Severity.CRITICAL),
  /** INV-7: the projection disagrees with the ledger for an account it has caught up with. */
  PROJECTION_MISMATCH(Severity.CRITICAL),
  /** The projection has stayed behind the ledger too long: an event lost or the consumer stuck. */
  PROJECTION_STALE(Severity.WARNING),
  /** The projection tables do not exist (the consumer has never run). */
  PROJECTION_UNAVAILABLE(Severity.WARNING),
  /** FR-10.2: a transaction has been PENDING too long; the PSP cannot confirm its outcome. */
  STUCK_PENDING(Severity.WARNING),
  /** FR-10.2: an outbox event has been unpublished too long: Kafka or the relay is stuck. */
  OUTBOX_LAG(Severity.WARNING);

  /** How bad a violation of this check is. */
  public enum Severity {
    CRITICAL,
    WARNING
  }

  private final Severity severity;

  Check(Severity severity) {
    this.severity = severity;
  }

  public Severity severity() {
    return severity;
  }
}
