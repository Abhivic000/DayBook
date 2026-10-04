package dev.daybook.api.idempotency.domain;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A request's final answer, stored so a retry receives it byte-for-byte.
 *
 * @param status HTTP status code
 * @param body serialised response body, stored as text so it is replayed exactly
 * @param transactionId the financial transaction this request produced, if any
 */
public record IdempotentResponse(int status, String body, @Nullable UUID transactionId) {

  public IdempotentResponse {
    Objects.requireNonNull(body, "body");
  }
}
