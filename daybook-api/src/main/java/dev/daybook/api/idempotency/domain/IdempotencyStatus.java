package dev.daybook.api.idempotency.domain;

public enum IdempotencyStatus {
  /** Claimed; the request's effect is not final yet. */
  IN_PROGRESS,
  /** The request has a final, stored response. */
  COMPLETED
}
