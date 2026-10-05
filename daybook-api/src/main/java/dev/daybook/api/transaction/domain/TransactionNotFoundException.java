package dev.daybook.api.transaction.domain;

import dev.daybook.api.common.domain.NotFoundException;
import java.util.UUID;

public class TransactionNotFoundException extends NotFoundException {

  public TransactionNotFoundException(UUID transactionId) {
    super("Transaction %s not found".formatted(transactionId));
  }
}
