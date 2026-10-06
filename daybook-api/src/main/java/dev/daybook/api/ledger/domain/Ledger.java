package dev.daybook.api.ledger.domain;

import dev.daybook.api.account.domain.Account;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies postings to accounts. Pure logic: the caller supplies the (already locked) accounts and
 * persists the returned ones together with the posting's entries, in one database transaction.
 */
public final class Ledger {

  private Ledger() {}

  /** One entry and the state of its account immediately after that entry was applied. */
  public record AppliedEntry(Entry entry, Account accountAfter) {}

  /**
   * The outcome of applying a posting.
   *
   * @param accounts final state of every supplied account, keyed by id
   * @param trail each entry in posting order, with its account's state right after it — the version
   *     and balance each published event carries (ADR 0010)
   */
  public record Result(Map<UUID, Account> accounts, List<AppliedEntry> trail) {}

  /**
   * Applies every entry of {@code posting} and returns the updated accounts, keyed by id.
   *
   * @throws dev.daybook.api.account.domain.InsufficientFundsException if a debit would take an
   *     account that may not go negative below zero
   * @throws dev.daybook.api.account.domain.AccountNotActiveException if any account is not active
   * @throws IllegalArgumentException if an account referenced by the posting was not supplied
   */
  public static Map<UUID, Account> apply(Posting posting, Collection<Account> accounts) {
    return applyWithTrail(posting, accounts).accounts();
  }

  /** As {@link #apply}, also returning the per-entry trail. */
  public static Result applyWithTrail(Posting posting, Collection<Account> accounts) {
    Map<UUID, Account> updated = new LinkedHashMap<>();
    for (Account account : accounts) {
      updated.put(account.id(), account);
    }
    List<AppliedEntry> trail = new ArrayList<>();
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
      trail.add(new AppliedEntry(entry, next));
    }
    return new Result(Map.copyOf(updated), List.copyOf(trail));
  }
}
