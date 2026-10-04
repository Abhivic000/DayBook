package dev.daybook.api.account.domain;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;
import java.util.UUID;

public class InsufficientFundsException extends DomainException {

  private final UUID accountId;
  private final Money balance;
  private final Money requested;

  public InsufficientFundsException(UUID accountId, Money balance, Money requested) {
    super(
        "Account balance %d is less than requested %d"
            .formatted(balance.minor(), requested.minor()));
    this.accountId = accountId;
    this.balance = balance;
    this.requested = requested;
  }

  @Override
  public String code() {
    return "insufficient-funds";
  }

  public UUID accountId() {
    return accountId;
  }

  public Money balance() {
    return balance;
  }

  public Money requested() {
    return requested;
  }
}
