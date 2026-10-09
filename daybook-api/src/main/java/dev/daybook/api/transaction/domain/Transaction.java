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
 * @param reversesTransactionId for a REVERSAL, the transaction it undoes (ADR 0003)
 */
public record Transaction(
    UUID id,
    UUID tenantId,
    TransactionType type,
    TransactionStatus status,
    Money amount,
    @Nullable UUID customerAccountId,
    @Nullable String pspReference,
    @Nullable UUID reversesTransactionId) {

  public Transaction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Transaction amount must be positive");
    }
    if ((type == TransactionType.REVERSAL) != (reversesTransactionId != null)) {
      throw new IllegalArgumentException(
          "Exactly the REVERSAL type references a reversed transaction");
    }
  }

  /** An internal transaction that settles immediately (transfer, funding). */
  public static Transaction settled(UUID id, UUID tenantId, TransactionType type, Money amount) {
    return new Transaction(id, tenantId, type, TransactionStatus.SETTLED, amount, null, null, null);
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
        id.toString(),
        null);
  }

  /**
   * A compensating transaction undoing {@code original} (ADR 0003). The original is never edited;
   * the database allows at most one reversal per original.
   */
  public static Transaction reversalOf(UUID id, Transaction original) {
    return new Transaction(
        id,
        original.tenantId(),
        TransactionType.REVERSAL,
        TransactionStatus.SETTLED,
        original.amount(),
        original.customerAccountId(),
        null,
        original.id());
  }

  public boolean isPending() {
    return status == TransactionStatus.PENDING;
  }
}
