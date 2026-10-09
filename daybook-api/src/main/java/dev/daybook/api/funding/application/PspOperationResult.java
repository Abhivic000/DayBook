package dev.daybook.api.funding.application;

import java.util.UUID;

/**
 * How a top-up or withdrawal ended when its HTTP request finished (ADR 0017).
 *
 * @param transactionId the transaction the request created
 */
public record PspOperationResult(Outcome outcome, UUID transactionId) {

  /** What the client is told. */
  public enum Outcome {
    /** The PSP moved the money and the ledger reflects it: 201. */
    SETTLED,
    /** The PSP definitively refused: 422 payment-declined. */
    DECLINED,
    /** Outcome unknown; the transaction stays PENDING until resolved: 202. */
    PENDING,
    /** The PSP never accepted the request; nothing happened: 503, retryable with the same key. */
    NOT_ACCEPTED
  }
}
