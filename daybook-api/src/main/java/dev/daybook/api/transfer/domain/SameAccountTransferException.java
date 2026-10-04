package dev.daybook.api.transfer.domain;

import dev.daybook.api.common.domain.DomainException;
import java.util.UUID;

public class SameAccountTransferException extends DomainException {

  public SameAccountTransferException(UUID accountId) {
    super("Source and destination are the same account: " + accountId);
  }

  @Override
  public String code() {
    return "same-account-transfer";
  }
}
