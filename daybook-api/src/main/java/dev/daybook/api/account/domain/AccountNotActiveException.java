package dev.daybook.api.account.domain;

import dev.daybook.api.common.domain.DomainException;
import java.util.UUID;

public class AccountNotActiveException extends DomainException {

  private final UUID accountId;
  private final AccountStatus status;

  public AccountNotActiveException(UUID accountId, AccountStatus status) {
    super("Account %s is %s".formatted(accountId, status));
    this.accountId = accountId;
    this.status = status;
  }

  @Override
  public String code() {
    return "account-not-active";
  }

  public UUID accountId() {
    return accountId;
  }

  public AccountStatus status() {
    return status;
  }
}
