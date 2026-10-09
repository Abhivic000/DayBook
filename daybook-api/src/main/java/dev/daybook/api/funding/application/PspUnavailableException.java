package dev.daybook.api.funding.application;

/**
 * The PSP is not accepting requests (circuit breaker open, or the request provably never reached
 * it). Reported as 503 (FR-7.3). Nothing has been written, so retrying later is safe.
 */
public class PspUnavailableException extends RuntimeException {

  public PspUnavailableException(String reason) {
    super("The payment provider is unavailable: " + reason);
  }
}
