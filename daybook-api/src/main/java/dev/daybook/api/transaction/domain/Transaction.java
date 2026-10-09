package dev.daybook.api.transaction.domain;

import dev.daybook.api.common.domain.Money;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A logical financial operation; its money movement is recorded as ledger entries.
 *
 * @param customerAccountId for PSP transactions, the customer's account — recorded on the
 *     transaction because a PENDING top-up has no entries yet to say where the money goes
 * @param pspReference the idempotency reference sent to the PSP (this transaction's id)
 */
public record Transaction(
    UUID id,
    UUID tenantId,
    TransactionType type,
    TransactionStatus status,
    Money amount,
    @Nullable UUID customerAccountId,
    @Nullable String pspReference) {

  public Transaction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Transaction amount must be positive");
    }
  }

  /** An internal transaction that settles immediately (transfer, funding). */
  public static Transaction settled(UUID id, UUID tenantId, TransactionType type, Money amount) {
    return new Transaction(id, tenantId, type, TransactionStatus.SETTLED, amount, null, null);
  }

  /**
   * A transaction whose outcome depends on the PSP. Its own id doubles as the PSP reference, so
   * every retry and status query refers to the same payment (ADR 0016).
   */
  public static Transaction pendingWithPsp(
      UUID id, UUID tenantId, TransactionType type, Money amount, UUID customerAccountId) {
    return new Transaction(
        id,
        tenantId,
        type,
        TransactionStatus.PENDING,
        amount,
        Objects.requireNonNull(customerAccountId, "customerAccountId"),
        id.toString());
  }

  public boolean isPending() {
    return status == TransactionStatus.PENDING;
  }
}
