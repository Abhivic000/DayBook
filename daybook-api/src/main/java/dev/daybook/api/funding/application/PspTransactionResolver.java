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
 * Turns a PSP outcome into a final transaction status, for top-ups and withdrawals alike. Used by
 * the request that created the transaction and by the sweeper (step 4), so each method first takes
 * the transaction's row lock and re-checks it is still PENDING: whichever arrives second sees it
 * already resolved and does nothing. Two resolutions can never both apply.
 *
 * <pre>
 *                 settle (PSP moved the money)              fail (it definitively did not)
 * TOPUP       DEBIT PSP_SETTLEMENT / CREDIT customer       nothing was posted: mark FAILED
 * WITHDRAWAL  DEBIT IN_TRANSIT / CREDIT PSP_SETTLEMENT     REVERSAL returns the hold:
 *             (the hold leaves for the PSP)                DEBIT IN_TRANSIT / CREDIT customer
 * </pre>
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
   * The PSP moved the money: post the transaction's final leg and mark it SETTLED. Returns the
   * transaction's status afterwards.
   *
   * @throws dev.daybook.api.common.domain.DomainException if posting is now impossible (e.g. the
   *     account was frozen meanwhile); the transaction then stays PENDING for an operator
   */
  @Transactional
  public TransactionStatus settle(UUID tenantId, UUID transactionId) {
    Transaction transaction = lockedOrFail(tenantId, transactionId);
    if (!transaction.isPending()) {
      return transaction.status();
    }
    UUID pspSettlement = systemAccount(tenantId, AccountType.PSP_SETTLEMENT);
    Posting finalLeg =
        switch (transaction.type()) {
          case TOPUP ->
              Posting.move(pspSettlement, transaction.customerAccountId(), transaction.amount());
          case WITHDRAWAL ->
              Posting.move(
                  systemAccount(tenantId, AccountType.WITHDRAWAL_IN_TRANSIT),
                  pspSettlement,
                  transaction.amount());
          default -> throw notPspTransaction(transaction);
        };
    postingService.settlePending(transaction, finalLeg, locked -> {});
    return TransactionStatus.SETTLED;
  }

  /**
   * The money definitively did not move: undo any hold and mark FAILED. Returns the transaction's
   * status afterwards.
   *
   * @throws dev.daybook.api.common.domain.DomainException if a hold cannot be returned now (e.g.
   *     the account was frozen meanwhile); the transaction then stays PENDING for an operator
   */
  @Transactional
  public TransactionStatus fail(UUID tenantId, UUID transactionId, String reason) {
    Transaction transaction = lockedOrFail(tenantId, transactionId);
    if (!transaction.isPending()) {
      return transaction.status();
    }
    switch (transaction.type()) {
      case TOPUP -> {} // nothing was posted
      case WITHDRAWAL ->
          // Compensating transaction (ADR 0003): the hold is returned by a new REVERSAL, never by
          // editing the original. The database allows at most one reversal per original.
          postingService.post(
              Transaction.reversalOf(UUID.randomUUID(), transaction),
              Posting.move(
                  systemAccount(tenantId, AccountType.WITHDRAWAL_IN_TRANSIT),
                  transaction.customerAccountId(),
                  transaction.amount()),
              locked -> {});
      default -> throw notPspTransaction(transaction);
    }
    transactions.markFailed(transactionId, reason);
    return TransactionStatus.FAILED;
  }

  private UUID systemAccount(UUID tenantId, AccountType type) {
    return accounts
        .findSystemAccount(tenantId, type)
        .orElseThrow(() -> new IllegalStateException("Tenant has no " + type + " account"))
        .id();
  }

  private Transaction lockedOrFail(UUID tenantId, UUID transactionId) {
    return transactions
        .lockForUpdate(tenantId, transactionId)
        .orElseThrow(
            () -> new IllegalStateException("Transaction %s not found".formatted(transactionId)));
  }

  private static IllegalStateException notPspTransaction(Transaction transaction) {
    return new IllegalStateException(
        "Transaction %s of type %s does not involve the PSP"
            .formatted(transaction.id(), transaction.type()));
  }
}
