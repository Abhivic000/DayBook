package dev.daybook.api.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountStatus;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.account.domain.InsufficientFundsException;
import dev.daybook.api.common.domain.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTest {

  private static final UUID TENANT = UUID.randomUUID();

  @Test
  void transferMovesMoneyAndConservesTheTotal() {
    Account from = user(1_000);
    Account to = user(250);

    Map<UUID, Account> result =
        Ledger.apply(Posting.move(from.id(), to.id(), Money.ofMinor(400)), List.of(from, to));

    assertThat(result.get(from.id()).balance()).isEqualTo(Money.ofMinor(600));
    assertThat(result.get(to.id()).balance()).isEqualTo(Money.ofMinor(650));
    assertThat(total(result)).isEqualTo(Money.ofMinor(1_250));
  }

  @Test
  void fundingFromTreasuryKeepsTheSystemTotalAtZero() {
    Account treasury = Account.open(UUID.randomUUID(), TENANT, AccountType.TREASURY);
    Account user = Account.open(UUID.randomUUID(), TENANT, AccountType.USER);

    Map<UUID, Account> result =
        Ledger.apply(
            Posting.move(treasury.id(), user.id(), Money.ofMinor(10_000)), List.of(treasury, user));

    assertThat(result.get(treasury.id()).balance()).isEqualTo(Money.ofMinor(-10_000));
    assertThat(result.get(user.id()).balance()).isEqualTo(Money.ofMinor(10_000));
    assertThat(total(result)).isEqualTo(Money.ZERO);
  }

  @Test
  void rejectedPostingLeavesInputAccountsUntouched() {
    Account from = user(100);
    Account to = user(0);

    assertThatThrownBy(
            () ->
                Ledger.apply(
                    Posting.move(from.id(), to.id(), Money.ofMinor(101)), List.of(from, to)))
        .isInstanceOf(InsufficientFundsException.class);
    assertThat(from.balance()).isEqualTo(Money.ofMinor(100));
    assertThat(to.balance()).isEqualTo(Money.ZERO);
  }

  @Test
  void trailRecordsEachEntryWithItsAccountStateAfterward() {
    Account from = user(1_000);
    Account to = user(0);

    Ledger.Result result =
        Ledger.applyWithTrail(
            Posting.move(from.id(), to.id(), Money.ofMinor(300)), List.of(from, to));

    assertThat(result.trail()).hasSize(2);
    Ledger.AppliedEntry debit = result.trail().get(0);
    assertThat(debit.entry().direction()).isEqualTo(Direction.DEBIT);
    assertThat(debit.accountAfter().balance()).isEqualTo(Money.ofMinor(700));
    assertThat(debit.accountAfter().version()).isEqualTo(1);
    Ledger.AppliedEntry credit = result.trail().get(1);
    assertThat(credit.accountAfter().id()).isEqualTo(to.id());
    assertThat(credit.accountAfter().balance()).isEqualTo(Money.ofMinor(300));
  }

  @Test
  void failsWhenAnAccountIsNotSupplied() {
    Account from = user(100);

    assertThatThrownBy(
            () ->
                Ledger.apply(
                    Posting.move(from.id(), UUID.randomUUID(), Money.ofMinor(1)), List.of(from)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Account user(long balance) {
    return new Account(
        UUID.randomUUID(),
        TENANT,
        AccountType.USER,
        Money.ofMinor(balance),
        0,
        false,
        AccountStatus.ACTIVE);
  }

  private static Money total(Map<UUID, Account> accounts) {
    return accounts.values().stream().map(Account::balance).reduce(Money.ZERO, Money::plus);
  }
}
