package dev.daybook.api.idempotency.domain;

/**
 * The original request with this key has not reached a final answer yet (FR-5.6). Reported as 409;
 * the client should retry later.
 */
public class IdempotencyRequestInProgressException extends RuntimeException {

  public IdempotencyRequestInProgressException(String key) {
    super("A request with idempotency key '%s' is still in progress".formatted(key));
  }
}
