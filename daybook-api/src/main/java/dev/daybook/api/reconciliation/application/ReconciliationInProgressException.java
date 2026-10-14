package dev.daybook.api.reconciliation.application;

/** Another reconciliation run holds the run slot; reported as 409. */
public class ReconciliationInProgressException extends RuntimeException {

  public ReconciliationInProgressException() {
    super("A reconciliation run is already in progress");
  }
}
