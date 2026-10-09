package dev.daybook.api.idempotency.application;

import dev.daybook.api.idempotency.domain.IdempotencyRecord;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

  /**
   * Claims {@code (tenantId, key)} as IN_PROGRESS. Returns {@code false} if the key already exists.
   *
   * <p>If another transaction has claimed the same key but not yet committed, this blocks until it
   * finishes: if it commits, the claim fails; if it rolls back, the claim succeeds. The unique
   * constraint, not application code, is the concurrency control (INV-5).
   */
  boolean tryClaim(UUID tenantId, String key, String requestHash, Duration retention);

  Optional<IdempotencyRecord> find(UUID tenantId, String key);

  void complete(UUID tenantId, String key, IdempotentResponse response);

  /** Records which transaction an IN_PROGRESS key started (two-phase requests). */
  void linkTransaction(UUID tenantId, String key, UUID transactionId);

  /** Deletes an IN_PROGRESS key, so the request can be retried with it as if never made. */
  void release(UUID tenantId, String key);

  /** Deletes records past their expiry and returns how many were removed (FR-5.7). */
  int deleteExpired();
}
