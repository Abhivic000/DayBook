package dev.daybook.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.FundingService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds test data through the real services and checks the ledger invariants with plain SQL — the
 * same checks reconciliation will run in Phase 4.
 */
@Component
public class LedgerTestData {

  private final JdbcClient jdbc;
  private final AccountRepository accounts;
  private final FundingService funding;

  LedgerTestData(JdbcClient jdbc, AccountRepository accounts, FundingService funding) {
    this.jdbc = jdbc;
    this.accounts = accounts;
    this.funding = funding;
  }

  /** A new tenant with its TREASURY account. */
  public UUID newTenant() {
    UUID tenantId = UUID.randomUUID();
    jdbc.sql("INSERT INTO tenants (id, name) VALUES (?, ?)")
        .params(tenantId, "tenant-" + tenantId)
        .update();
    accounts.insert(Account.open(UUID.randomUUID(), tenantId, AccountType.TREASURY));
    return tenantId;
  }

  public UUID newUserAccount(UUID tenantId) {
    UUID id = UUID.randomUUID();
    accounts.insert(Account.open(id, tenantId, AccountType.USER));
    return id;
  }

  public UUID newFundedUserAccount(UUID tenantId, long balanceMinor) {
    UUID id = newUserAccount(tenantId);
    fund(tenantId, id, balanceMinor);
    return id;
  }

  public void fund(UUID tenantId, UUID accountId, long amountMinor) {
    funding.fund(tenantId, accountId, Money.ofMinor(amountMinor));
  }

  public UUID treasuryOf(UUID tenantId) {
    return accounts.findSystemAccount(tenantId, AccountType.TREASURY).orElseThrow().id();
  }

  public long balanceOf(UUID accountId) {
    return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = ?")
        .param(accountId)
        .query(Long.class)
        .single();
  }

  public long entryCount(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = ?")
        .param(accountId)
        .query(Long.class)
        .single();
  }

  public long transactionCount(UUID tenantId) {
    return jdbc.sql("SELECT count(*) FROM transactions WHERE tenant_id = ?")
        .param(tenantId)
        .query(Long.class)
        .single();
  }

  /**
   * Asserts INV-1/2 (the tenant's entries net to zero) and INV-3 (every cached balance equals the
   * sum of its entries).
   */
  public void assertLedgerConsistent(UUID tenantId) {
    long net =
        jdbc.sql(
                """
                SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount_minor
                                                   ELSE -amount_minor END), 0)
                  FROM ledger_entries WHERE tenant_id = ?
                """)
            .param(tenantId)
            .query(Long.class)
            .single();
    assertThat(net).as("debits minus credits for tenant").isZero();

    var drifted =
        jdbc.sql(
                """
                SELECT a.id
                  FROM accounts a
                  LEFT JOIN ledger_entries e ON e.account_id = a.id
                 WHERE a.tenant_id = ?
                 GROUP BY a.id, a.balance_minor
                HAVING a.balance_minor <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT'
                                                       THEN e.amount_minor
                                                       ELSE -e.amount_minor END), 0)
                """)
            .param(tenantId)
            .query(UUID.class)
            .list();
    assertThat(drifted).as("accounts whose cached balance drifted from entries").isEmpty();
  }
}
