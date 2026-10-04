package dev.daybook.api.ledger.domain;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;

public class AmountLimitExceededException extends DomainException {

  private final Money amount;
  private final Money limit;

  public AmountLimitExceededException(Money amount, Money limit) {
    super(
        "Amount %d exceeds the per-transaction limit of %d"
            .formatted(amount.minor(), limit.minor()));
    this.amount = amount;
    this.limit = limit;
  }

  @Override
  public String code() {
    return "amount-limit-exceeded";
  }

  public Money amount() {
    return amount;
  }

  public Money limit() {
    return limit;
  }
}
