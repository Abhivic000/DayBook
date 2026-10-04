package dev.daybook.api.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.common.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final UUID TENANT = UUID.randomUUID();

  @Test
  void creditIncreasesBalanceAndVersion() {
    Account account = user(100).credit(Money.ofMinor(50));

    assertThat(account.balance()).isEqualTo(Money.ofMinor(150));
    assertThat(account.version()).isEqualTo(1);
  }

  @Test
  void debitDecreasesBalanceAndVersion() {
    Account account = user(100).debit(Money.ofMinor(30));

    assertThat(account.balance()).isEqualTo(Money.ofMinor(70));
    assertThat(account.version()).isEqualTo(1);
  }

  @Test
  void userAccountCanBeDebitedToExactlyZero() {
    assertThat(user(100).debit(Money.ofMinor(100)).balance()).isEqualTo(Money.ZERO);
  }

  @Test
  void userAccountCannotGoNegative() {
    Account account = user(100);

    assertThatThrownBy(() -> account.debit(Money.ofMinor(101)))
        .isInstanceOfSatisfying(
            InsufficientFundsException.class,
            e -> {
              assertThat(e.code()).isEqualTo("insufficient-funds");
              assertThat(e.balance()).isEqualTo(Money.ofMinor(100));
              assertThat(e.requested()).isEqualTo(Money.ofMinor(101));
            });
  }

  @Test
  void treasuryAccountMayGoNegative() {
    Account treasury = Account.open(UUID.randomUUID(), TENANT, AccountType.TREASURY);

    assertThat(treasury.allowNegative()).isTrue();
    assertThat(treasury.debit(Money.ofMinor(500)).balance()).isEqualTo(Money.ofMinor(-500));
  }

  @Test
  void frozenAccountRejectsDebitsAndCredits() {
    Account frozen =
        new Account(
            UUID.randomUUID(),
            TENANT,
            AccountType.USER,
            Money.ofMinor(100),
            0,
            false,
            AccountStatus.FROZEN);

    assertThatThrownBy(() -> frozen.debit(Money.ofMinor(1)))
        .isInstanceOf(AccountNotActiveException.class);
    assertThatThrownBy(() -> frozen.credit(Money.ofMinor(1)))
        .isInstanceOf(AccountNotActiveException.class);
  }

  @Test
  void rejectsNonPositiveAmounts() {
    Account account = user(100);

    assertThatThrownBy(() -> account.debit(Money.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> account.credit(Money.ofMinor(-5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void userAccountCannotBeConstructedToAllowNegative() {
    assertThatThrownBy(
            () ->
                new Account(
                    UUID.randomUUID(),
                    TENANT,
                    AccountType.USER,
                    Money.ZERO,
                    0,
                    true,
                    AccountStatus.ACTIVE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isImmutable() {
    Account original = user(100);
    original.debit(Money.ofMinor(40));

    assertThat(original.balance()).isEqualTo(Money.ofMinor(100));
    assertThat(original.version()).isZero();
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
}
