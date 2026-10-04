package dev.daybook.api.account.domain;

import dev.daybook.api.common.domain.DomainException;
import java.util.UUID;

/** The operation only accepts certain account types, e.g. transfers only between USER accounts. */
public class AccountTypeNotAllowedException extends DomainException {

  private final UUID accountId;
  private final AccountType type;

  public AccountTypeNotAllowedException(UUID accountId, AccountType type) {
    super("Account %s of type %s is not allowed in this operation".formatted(accountId, type));
    this.accountId = accountId;
    this.type = type;
  }

  @Override
  public String code() {
    return "account-type-not-allowed";
  }

  public UUID accountId() {
    return accountId;
  }

  public AccountType type() {
    return type;
  }
}
