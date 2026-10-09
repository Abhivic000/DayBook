package dev.daybook.api.transaction.application;

import dev.daybook.api.transaction.domain.Transaction;
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

  /** PENDING → SETTLED. Fails if the transaction is not PENDING. */
  void markSettled(UUID transactionId);

  /** PENDING → FAILED. Fails if the transaction is not PENDING. */
  void markFailed(UUID transactionId, String reason);
}
