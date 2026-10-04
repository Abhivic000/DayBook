package dev.daybook.api.idempotency.domain;

import dev.daybook.api.common.domain.DomainException;

/**
 * The key was already used for a different request (FR-5.5). Reported as 422, but never stored: the
 * key's stored answer belongs to the original request.
 */
public class IdempotencyKeyReusedException extends DomainException {

  public IdempotencyKeyReusedException(String key) {
    super("Idempotency key '%s' was already used with a different request".formatted(key));
  }

  @Override
  public String code() {
    return "idempotency-key-reused";
  }
}
