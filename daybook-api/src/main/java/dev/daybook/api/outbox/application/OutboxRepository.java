package dev.daybook.api.outbox.application;

import dev.daybook.api.outbox.domain.OutboxMessage;
import java.time.Duration;
import java.util.List;

public interface OutboxRepository {

  /** An outbox row awaiting publication. */
  record PendingMessage(long id, OutboxMessage message) {}

  /** Appends a message. Must run inside the business transaction that produced it. */
  void append(OutboxMessage message);

  /**
   * Tries to become the single active relay for the current transaction (ADR 0004), via a
   * transaction-scoped advisory lock released automatically at commit or rollback.
   */
  boolean tryLockRelay();

  /** Unpublished messages in insertion order. */
  List<PendingMessage> fetchUnpublished(int limit);

  void markPublished(List<Long> ids);

  void markFailed(long id, String error);

  /** Deletes messages published longer ago than {@code age}; returns how many (FR-8.6). */
  int deletePublishedOlderThan(Duration age);
}
