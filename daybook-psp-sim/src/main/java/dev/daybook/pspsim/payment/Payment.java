package dev.daybook.pspsim.payment;

import java.time.Instant;

/**
 * A payment as the PSP records it.
 *
 * @param reference the caller's idempotency reference (Daybook sends its transaction id)
 */
public record Payment(
    String reference, Direction direction, long amountMinor, Status status, Instant createdAt) {

  /** Which way money moves, from the merchant's (Daybook's) point of view. */
  public enum Direction {
    /** Money comes in from the customer's card/bank: a Daybook top-up. */
    COLLECT,
    /** Money goes out to the customer's bank: a Daybook withdrawal. */
    PAYOUT
  }

  /** A payment's final status. This simulator decides outcomes instantly. */
  public enum Status {
    SUCCEEDED,
    /** Definitive failure: the money did not and will not move. */
    DECLINED
  }

  /** Whether a repeated request with this payment's reference describes the same payment. */
  public boolean sameRequestAs(Direction otherDirection, long otherAmountMinor) {
    return direction == otherDirection && amountMinor == otherAmountMinor;
  }
}
