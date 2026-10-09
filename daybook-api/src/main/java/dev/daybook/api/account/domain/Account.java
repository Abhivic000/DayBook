package dev.daybook.api.account.domain;

import dev.daybook.api.common.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * An account and its cached balance. Immutable: {@link #debit} and {@link #credit} return a new
 * instance with the updated balance and an incremented version, leaving persistence to the caller.
 *
 * <p>Sign convention (INV-3): {@code balance = sum(credits) - sum(debits)}. A debit reduces the
 * balance; a credit increases it.
 */
public record Account(
    UUID id,
    UUID tenantId,
    AccountType type,
    Money balance,
    long version,
    boolean allowNegative,
    AccountStatus status) {

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(balance, "balance");
    Objects.requireNonNull(status, "status");
    if (allowNegative && !type.mayGoNegative()) {
      throw new IllegalArgumentException(type + " accounts cannot allow a negative balance");
    }
  }

  /** A new, empty account of the given type, allowed to go negative only if its type may. */
  public static Account open(UUID id, UUID tenantId, AccountType type) {
    return new Account(
        id, tenantId, type, Money.ZERO, 0, type.mayGoNegative(), AccountStatus.ACTIVE);
  }

  public Account debit(Money amount) {
    requirePositive(amount);
    requireActive();
    Money newBalance = balance.minus(amount);
    if (newBalance.isNegative() && !allowNegative) {
      throw new InsufficientFundsException(id, balance, amount);
    }
    return withBalance(newBalance);
  }

  public Account credit(Money amount) {
    requirePositive(amount);
    requireActive();
    return withBalance(balance.plus(amount));
  }

  private Account withBalance(Money newBalance) {
    return new Account(id, tenantId, type, newBalance, version + 1, allowNegative, status);
  }

  private void requireActive() {
    if (status != AccountStatus.ACTIVE) {
      throw new AccountNotActiveException(id, status);
    }
  }

  private static void requirePositive(Money amount) {
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("Amount must be positive, got " + amount.minor());
    }
  }
}
