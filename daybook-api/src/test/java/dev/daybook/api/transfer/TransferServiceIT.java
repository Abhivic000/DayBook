package dev.daybook.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.TestcontainersConfiguration;
import dev.daybook.api.account.domain.AccountNotFoundException;
import dev.daybook.api.account.domain.AccountTypeNotAllowedException;
import dev.daybook.api.account.domain.InsufficientFundsException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.ledger.domain.AmountLimitExceededException;
import dev.daybook.api.support.LedgerTestData;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import dev.daybook.api.transfer.application.TransferCommand;
import dev.daybook.api.transfer.application.TransferService;
import dev.daybook.api.transfer.domain.SameAccountTransferException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "daybook.ledger.max-transaction-amount-minor=1000000")
@Import(TestcontainersConfiguration.class)
class TransferServiceIT {

  @Autowired TransferService transfers;
  @Autowired LedgerTestData data;

  @Test
  void movesMoneyAndRecordsBalancedEntries() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 10_000);
    UUID to = data.newUserAccount(tenant);

    Transaction txn =
        transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(2_500)));

    assertThat(txn.type()).isEqualTo(TransactionType.TRANSFER);
    assertThat(txn.status()).isEqualTo(TransactionStatus.SETTLED);
    assertThat(data.balanceOf(from)).isEqualTo(7_500);
    assertThat(data.balanceOf(to)).isEqualTo(2_500);
    assertThat(data.entryCount(to)).isEqualTo(1);
    data.assertLedgerConsistent(tenant);
  }

  @Test
  void insufficientFundsWritesNothing() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 1_000);
    UUID to = data.newUserAccount(tenant);
    long transactionsBefore = data.transactionCount(tenant);

    assertThatThrownBy(
            () -> transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(1_001))))
        .isInstanceOf(InsufficientFundsException.class);

    assertThat(data.balanceOf(from)).isEqualTo(1_000);
    assertThat(data.balanceOf(to)).isZero();
    assertThat(data.transactionCount(tenant)).isEqualTo(transactionsBefore);
    data.assertLedgerConsistent(tenant);
  }

  @Test
  void rejectsTransferToSameAccount() {
    UUID tenant = data.newTenant();
    UUID account = data.newFundedUserAccount(tenant, 1_000);

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(tenant, account, account, Money.ofMinor(100))))
        .isInstanceOf(SameAccountTransferException.class);
  }

  @Test
  void anotherTenantsAccountIsNotFound() {
    UUID tenantA = data.newTenant();
    UUID tenantB = data.newTenant();
    UUID fromA = data.newFundedUserAccount(tenantA, 1_000);
    UUID accountOfB = data.newUserAccount(tenantB);

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(tenantA, fromA, accountOfB, Money.ofMinor(100))))
        .isInstanceOf(AccountNotFoundException.class);
    assertThat(data.balanceOf(fromA)).isEqualTo(1_000);
  }

  @Test
  void cannotTransferOutOfTreasury() {
    UUID tenant = data.newTenant();
    UUID user = data.newUserAccount(tenant);

    assertThatThrownBy(
            () ->
                transfers.transfer(
                    new TransferCommand(
                        tenant, data.treasuryOf(tenant), user, Money.ofMinor(1_000_000))))
        .isInstanceOf(AccountTypeNotAllowedException.class);
    assertThat(data.balanceOf(user)).isZero();
  }

  @Test
  void rejectsAmountsAboveTheConfiguredLimit() {
    UUID tenant = data.newTenant();
    UUID from = data.newFundedUserAccount(tenant, 1_000_000);
    UUID to = data.newUserAccount(tenant);

    assertThatThrownBy(
            () ->
                transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(1_000_001))))
        .isInstanceOf(AmountLimitExceededException.class);
  }
}
