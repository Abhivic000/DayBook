package dev.daybook.api.transaction.domain;

import dev.daybook.api.common.domain.Money;
import java.util.Objects;
import java.util.UUID;

/** A logical financial operation; its money movement is recorded as ledger entries. */
public record Transaction(
    UUID id, UUID tenantId, TransactionType type, TransactionStatus status, Money amount) {

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

  public static Transaction settled(UUID id, UUID tenantId, TransactionType type, Money amount) {
    return new Transaction(id, tenantId, type, TransactionStatus.SETTLED, amount);
  }
}
