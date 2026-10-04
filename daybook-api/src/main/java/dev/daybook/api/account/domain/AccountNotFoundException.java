package dev.daybook.api.account.domain;

import dev.daybook.api.common.domain.NotFoundException;
import java.util.UUID;

public class AccountNotFoundException extends NotFoundException {

  private final UUID accountId;

  public AccountNotFoundException(UUID accountId) {
    super("Account %s not found".formatted(accountId));
    this.accountId = accountId;
  }

  public UUID accountId() {
    return accountId;
  }
}
