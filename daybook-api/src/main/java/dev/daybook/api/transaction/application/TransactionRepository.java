package dev.daybook.api.transaction.application;

import dev.daybook.api.transaction.domain.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

  void insert(Transaction transaction);

  /**
   * Row-locks the transaction until the surrounding database transaction ends. Whoever resolves a
   * PENDING transaction (the request that created it, or the sweeper) takes this lock first, so two
   * resolutions can never both apply.
   */
  Optional<Transaction> lockForUpdate(UUID tenantId, UUID transactionId);

  /** A PENDING transaction and when it was created. */
  record PendingTransaction(Transaction transaction, Instant createdAt) {}

  /**
   * PENDING PSP transactions (top-ups, withdrawals) older than {@code minAge}, oldest first, across
   * all tenants. Not locked: the caller resolves each one under its own row lock.
   */
  List<PendingTransaction> findPendingWithPsp(Duration minAge, int limit);

  /** PENDING → SETTLED. Fails if the transaction is not PENDING. */
  void markSettled(UUID transactionId);

  /** PENDING → FAILED. Fails if the transaction is not PENDING. */
  void markFailed(UUID transactionId, String reason);
}
