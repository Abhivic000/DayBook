package dev.daybook.api.transfer.application;

import dev.daybook.api.common.domain.Money;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(UUID tenantId, UUID fromAccountId, UUID toAccountId, Money amount) {

  public TransferCommand {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(fromAccountId, "fromAccountId");
    Objects.requireNonNull(toAccountId, "toAccountId");
    Objects.requireNonNull(amount, "amount");
  }
}
