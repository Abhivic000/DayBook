package dev.daybook.api.ledger.domain;

import dev.daybook.api.account.domain.Account;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Applies postings to accounts. Pure logic: the caller supplies the (already locked) accounts and
 * persists the returned ones together with the posting's entries, in one database transaction.
 */
public final class Ledger {

  private Ledger() {}

  /**
   * Applies every entry of {@code posting} and returns the updated accounts, keyed by id.
   *
   * @throws dev.daybook.api.account.domain.InsufficientFundsException if a debit would take an
   *     account that may not go negative below zero
   * @throws dev.daybook.api.account.domain.AccountNotActiveException if any account is not active
   * @throws IllegalArgumentException if an account referenced by the posting was not supplied
   */
  public static Map<UUID, Account> apply(Posting posting, Collection<Account> accounts) {
    Map<UUID, Account> updated = new LinkedHashMap<>();
    for (Account account : accounts) {
      updated.put(account.id(), account);
    }
    for (Entry entry : posting.entries()) {
      Account account = updated.get(entry.accountId());
      if (account == null) {
        throw new IllegalArgumentException("Account not supplied: " + entry.accountId());
      }
      Account next =
          switch (entry.direction()) {
            case DEBIT -> account.debit(entry.amount());
            case CREDIT -> account.credit(entry.amount());
          };
      updated.put(next.id(), next);
    }
    return Map.copyOf(updated);
  }
}
