package dev.daybook.api.funding.application;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.ledger.application.PostingService;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a PSP outcome into a final transaction status. Used both by the request that created the
 * transaction and (Phase 3 step 4) by the sweeper, so each method first takes the transaction's row
 * lock and re-checks it is still PENDING: whichever arrives second sees it already resolved and
 * does nothing. Two resolutions can never both apply.
 */
@Service
public class PspTransactionResolver {

  private final TransactionRepository transactions;
  private final AccountRepository accounts;
  private final PostingService postingService;

  PspTransactionResolver(
      TransactionRepository transactions,
      AccountRepository accounts,
      PostingService postingService) {
    this.transactions = transactions;
    this.accounts = accounts;
    this.postingService = postingService;
  }

  /**
   * The PSP collected the money: credit the customer (DEBIT PSP_SETTLEMENT, CREDIT customer) and
   * mark the top-up SETTLED. Returns the transaction's status afterwards.
   *
   * @throws dev.daybook.api.common.domain.DomainException if the credit is now impossible (e.g. the
   *     account was frozen meanwhile); the transaction then stays PENDING for an operator
   */
  @Transactional
  public TransactionStatus settleTopUp(UUID tenantId, UUID transactionId) {
    Transaction transaction = lockedOrFail(tenantId, transactionId);
    if (!transaction.isPending()) {
      return transaction.status();
    }
    UUID pspSettlement =
        accounts
            .findSystemAccount(tenantId, AccountType.PSP_SETTLEMENT)
            .orElseThrow(() -> new IllegalStateException("Tenant has no PSP_SETTLEMENT account"))
            .id();
    postingService.settlePending(
        transaction,
        Posting.move(pspSettlement, transaction.customerAccountId(), transaction.amount()),
        locked -> {});
    return TransactionStatus.SETTLED;
  }

  /** The money definitively did not move: mark FAILED. Returns the status afterwards. */
  @Transactional
  public TransactionStatus fail(UUID tenantId, UUID transactionId, String reason) {
    Transaction transaction = lockedOrFail(tenantId, transactionId);
    if (!transaction.isPending()) {
      return transaction.status();
    }
    transactions.markFailed(transactionId, reason);
    return TransactionStatus.FAILED;
  }

  private Transaction lockedOrFail(UUID tenantId, UUID transactionId) {
    return transactions
        .lockForUpdate(tenantId, transactionId)
        .orElseThrow(
            () -> new IllegalStateException("Transaction %s not found".formatted(transactionId)));
  }
}
