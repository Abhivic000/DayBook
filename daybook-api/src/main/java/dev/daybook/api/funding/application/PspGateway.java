package dev.daybook.api.funding.application;

import dev.daybook.api.common.domain.Money;

/**
 * What Daybook needs from a payment service provider. Implementations hide HTTP, timeouts, retries
 * and the circuit breaker, and report one of four outcomes — the distinction that matters for money
 * is whether the PSP <em>might have acted</em>.
 */
public interface PspGateway {

  /** Which way money moves through the PSP. */
  enum Direction {
    /** Money in from the customer: a top-up. */
    COLLECT,
    /** Money out to the customer: a withdrawal. */
    PAYOUT
  }

  /** The result of asking the PSP to move money. */
  sealed interface Outcome {

    /** The PSP moved the money. */
    record Succeeded() implements Outcome {}

    /** The PSP definitively refused; the money did not and will not move. */
    record Declined() implements Outcome {}

    /**
     * The request provably never took effect — the PSP was unreachable, rejected it before
     * accepting it, or our circuit breaker / bulkhead stopped it being sent. Safe to fail.
     */
    record NotAccepted(String reason) implements Outcome {}

    /**
     * The PSP may or may not have acted (e.g. it received the request but its answer never
     * arrived). Must stay PENDING until the PSP is asked (ADR 0016) — never guessed.
     */
    record Unknown(String reason) implements Outcome {}
  }

  /**
   * Asks the PSP to move money. {@code reference} is the PSP's idempotency key: repeating it can
   * never move money twice.
   */
  Outcome createPayment(String reference, Direction direction, Money amount);

  /** False while the circuit breaker is open: callers should fail fast without writing anything. */
  boolean acceptingRequests();
}
