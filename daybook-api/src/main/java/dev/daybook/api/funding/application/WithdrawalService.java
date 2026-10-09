package dev.daybook.api.funding.application;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.application.AccountService;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.ledger.application.LedgerProperties;
import dev.daybook.api.ledger.application.PostingService;
import dev.daybook.api.ledger.domain.AmountLimitExceededException;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionType;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Withdrawal: money out from a USER account to the PSP (FR-6, ADR 0006).
 *
 * <p>The funds are <em>held</em> — moved to WITHDRAWAL_IN_TRANSIT — before the PSP is asked to pay
 * them out. Without the hold, the user could spend the same money (e.g. transfer it away) while the
 * payout is in flight, and a successful payout would then drive the balance negative.
 */
@Service
public class WithdrawalService {

  /** A withdrawal request. */
  public record WithdrawalCommand(UUID tenantId, UUID accountId, Money amount) {
    public WithdrawalCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(amount, "amount");
      if (!amount.isPositive()) {
        throw new IllegalArgumentException("Withdrawal amount must be positive");
      }
    }
  }

  private final AccountService userAccounts;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final PostingService postingService;
  private final PspGateway psp;
  private final PspPayments payments;
  private final Money maxAmount;

  WithdrawalService(
      AccountService userAccounts,
      AccountRepository accounts,
      TransactionRepository transactions,
      PostingService postingService,
      PspGateway psp,
      PspPayments payments,
      LedgerProperties ledger) {
    this.userAccounts = userAccounts;
    this.accounts = accounts;
    this.transactions = transactions;
    this.postingService = postingService;
    this.psp = psp;
    this.payments = payments;
    this.maxAmount = Money.ofMinor(ledger.maxTransactionAmountMinor());
  }

  /**
   * Phase one: record a PENDING withdrawal and place the hold (DEBIT user / CREDIT IN_TRANSIT),
   * under the same row locks and funds check as a transfer. Commits before the PSP is contacted.
   *
   * @throws PspUnavailableException the circuit breaker is open — before anything is written
   * @throws dev.daybook.api.account.domain.InsufficientFundsException not enough to hold; nothing
   *     is written and the PSP is never called
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID begin(WithdrawalCommand command) {
    if (!psp.acceptingRequests()) {
      throw new PspUnavailableException("circuit breaker open");
    }
    if (command.amount().compareTo(maxAmount) > 0) {
      throw new AmountLimitExceededException(command.amount(), maxAmount);
    }
    userAccounts.getUserAccount(command.tenantId(), command.accountId()); // 404 unless USER
    UUID inTransit =
        accounts
            .findSystemAccount(command.tenantId(), AccountType.WITHDRAWAL_IN_TRANSIT)
            .orElseThrow(() -> new IllegalStateException("Tenant has no WITHDRAWAL_IN_TRANSIT"))
            .id();

    Transaction pending =
        Transaction.pendingWithPsp(
            UUID.randomUUID(),
            command.tenantId(),
            TransactionType.WITHDRAWAL,
            command.amount(),
            command.accountId());
    transactions.insert(pending);
    postingService.postToPending(
        pending, Posting.move(command.accountId(), inTransit, command.amount()), locked -> {});
    return pending.id();
  }

  /** Phase two: ask the PSP to pay out, then settle or reverse the hold. */
  public PspOperationResult complete(WithdrawalCommand command, UUID transactionId) {
    return payments.execute(
        command.tenantId(), transactionId, PspGateway.Direction.PAYOUT, command.amount());
  }
}
