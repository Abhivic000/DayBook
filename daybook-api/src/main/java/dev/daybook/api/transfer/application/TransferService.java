package dev.daybook.api.transfer.application;

import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.account.domain.AccountTypeNotAllowedException;
import dev.daybook.api.ledger.application.PostingService;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionType;
import dev.daybook.api.transfer.domain.SameAccountTransferException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal transfer between two USER accounts of one tenant; settles synchronously (FR-4). */
@Service
public class TransferService {

  private final PostingService postingService;

  TransferService(PostingService postingService) {
    this.postingService = postingService;
  }

  @Transactional
  public Transaction transfer(TransferCommand command) {
    if (command.fromAccountId().equals(command.toAccountId())) {
      throw new SameAccountTransferException(command.fromAccountId());
    }
    if (!command.amount().isPositive()) {
      throw new IllegalArgumentException("Transfer amount must be positive");
    }
    return postingService.post(
        command.tenantId(),
        TransactionType.TRANSFER,
        Posting.move(command.fromAccountId(), command.toAccountId(), command.amount()),
        locked -> locked.values().forEach(TransferService::requireUserAccount));
  }

  /**
   * System accounts may go negative; letting a tenant transfer out of one would create money. Only
   * USER accounts may take part in a transfer.
   */
  private static void requireUserAccount(Account account) {
    if (account.type() != AccountType.USER) {
      throw new AccountTypeNotAllowedException(account.id(), account.type());
    }
  }
}
