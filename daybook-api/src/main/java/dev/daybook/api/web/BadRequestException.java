package dev.daybook.api.web;

/** A malformed request detected by our own code (e.g. a bad cursor or idempotency key): 400. */
public class BadRequestException extends RuntimeException {

  private final String code;

  public BadRequestException(String code, String detail) {
    super(detail);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
