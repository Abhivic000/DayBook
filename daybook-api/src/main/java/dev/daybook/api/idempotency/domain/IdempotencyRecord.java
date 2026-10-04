package dev.daybook.api.idempotency.domain;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A stored idempotency key: who claimed it, for which request, and its final answer if any. */
public record IdempotencyRecord(
    UUID tenantId,
    String key,
    String requestHash,
    IdempotencyStatus status,
    @Nullable IdempotentResponse response) {

  public Optional<IdempotentResponse> completedResponse() {
    return status == IdempotencyStatus.COMPLETED ? Optional.ofNullable(response) : Optional.empty();
  }
}
