package dev.daybook.api.account.application;

import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountNotFoundException;
import dev.daybook.api.account.domain.AccountType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-facing account operations. Tenants only ever see their USER accounts. */
@Service
public class AccountService {

  private final AccountRepository accounts;

  AccountService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  @Transactional
  public Account openUserAccount(UUID tenantId) {
    Account account = Account.open(UUID.randomUUID(), tenantId, AccountType.USER);
    accounts.insert(account);
    return account;
  }

  /**
   * The tenant's USER account. System accounts are reported as not found, exactly like accounts
   * that do not exist or belong to another tenant.
   */
  public Account getUserAccount(UUID tenantId, UUID accountId) {
    return accounts
        .findById(tenantId, accountId)
        .filter(account -> account.type() == AccountType.USER)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
  }
}
