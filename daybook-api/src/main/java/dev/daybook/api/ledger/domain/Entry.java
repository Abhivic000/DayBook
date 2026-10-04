package dev.daybook.api.ledger.domain;

import dev.daybook.api.common.domain.Money;
import java.util.Objects;
import java.util.UUID;

/** One line of a posting: a positive amount debited from or credited to one account. */
public record Entry(UUID accountId, Direction direction, Money amount) {

  public Entry {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(amount, "amount");
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Entry amount must be positive, got " + amount.minor());
    }
  }

  public static Entry debit(UUID accountId, Money amount) {
    return new Entry(accountId, Direction.DEBIT, amount);
  }

  public static Entry credit(UUID accountId, Money amount) {
    return new Entry(accountId, Direction.CREDIT, amount);
  }
}
