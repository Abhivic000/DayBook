package dev.daybook.api.funding.application;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.account.domain.AccountTypeNotAllowedException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.ledger.application.PostingService;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only opening balances: moves money from the tenant's TREASURY account to a USER account
 * (ADR 0002 amendment). The treasury's balance is therefore always minus the total ever injected.
 */
@Service
public class FundingService {

  private final AccountRepository accounts;
  private final PostingService postingService;

  FundingService(AccountRepository accounts, PostingService postingService) {
    this.accounts = accounts;
    this.postingService = postingService;
  }

  @Transactional
  public Transaction fund(UUID tenantId, UUID userAccountId, Money amount) {
    Account treasury =
        accounts
            .findSystemAccount(tenantId, AccountType.TREASURY)
            .orElseThrow(
                () -> new IllegalStateException("Tenant %s has no TREASURY".formatted(tenantId)));
    if (treasury.id().equals(userAccountId)) {
      throw new AccountTypeNotAllowedException(treasury.id(), treasury.type());
    }
    return postingService.post(
        tenantId,
        TransactionType.FUNDING,
        Posting.move(treasury.id(), userAccountId, amount),
        locked -> {
          Account target = locked.get(userAccountId);
          if (target.type() != AccountType.USER) {
            throw new AccountTypeNotAllowedException(target.id(), target.type());
          }
        });
  }
}
