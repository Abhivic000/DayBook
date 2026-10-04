package dev.daybook.api.account.application;

import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for accounts. Every query is scoped to a tenant (FR-1.3). */
public interface AccountRepository {

  void insert(Account account);

  Optional<Account> findById(UUID tenantId, UUID accountId);

  Optional<Account> findSystemAccount(UUID tenantId, AccountType type);

  /**
   * Locks the given accounts with {@code SELECT ... FOR UPDATE} in ascending id order, and returns
   * those that exist for the tenant. Must be called inside a transaction; the locks are held until
   * it ends.
   *
   * <p>The ordering is done by the database, never in Java: every code path must lock in the same
   * order or opposing transfers deadlock, and Java's {@code UUID.compareTo} disagrees with
   * Postgres's uuid ordering.
   */
  List<Account> lockForUpdate(UUID tenantId, Collection<UUID> accountIds);

  /**
   * Writes the account's new balance and version. {@code expectedVersion} is the version read when
   * the row was locked; a mismatch means the lock was not held and fails loudly.
   */
  void updateBalance(Account account, long expectedVersion);
}
