package dev.daybook.api.ledger.application;

import dev.daybook.api.account.application.AccountRepository;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountNotFoundException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.ledger.domain.AmountLimitExceededException;
import dev.daybook.api.ledger.domain.Ledger;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single path by which money moves. Every use case (transfer, funding, later top-up and
 * withdrawal) posts through here, so locking, checks and persistence are written once.
 */
@Service
public class PostingService {

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final LedgerEntryRepository entries;
  private final AccountEntryEventWriter events;
  private final Money maxAmount;

  PostingService(
      AccountRepository accounts,
      TransactionRepository transactions,
      LedgerEntryRepository entries,
      AccountEntryEventWriter events,
      LedgerProperties properties) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.entries = entries;
    this.events = events;
    this.maxAmount = Money.ofMinor(properties.maxTransactionAmountMinor());
  }

  /**
   * Posts a settled transaction.
   *
   * <p>{@code MANDATORY}: the caller must already have opened the database transaction, because the
   * caller's own writes (e.g. the idempotency record) must commit or roll back together with these.
   *
   * @param guard use-case-specific checks on the locked accounts (e.g. "USER accounts only"); runs
   *     after locking and before any write
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Transaction post(
      UUID tenantId, TransactionType type, Posting posting, Consumer<Map<UUID, Account>> guard) {
    if (posting.amount().compareTo(maxAmount) > 0) {
      throw new AmountLimitExceededException(posting.amount(), maxAmount);
    }

    // 1. Lock. Ascending id order (in SQL) makes opposing transfers wait instead of deadlock.
    Set<UUID> ids = posting.accountIds();
    List<Account> locked = accounts.lockForUpdate(tenantId, ids);
    Map<UUID, Account> byId =
        locked.stream().collect(Collectors.toUnmodifiableMap(Account::id, a -> a));
    for (UUID id : ids) {
      if (!byId.containsKey(id)) {
        throw new AccountNotFoundException(id); // missing, or belongs to another tenant
      }
    }

    // 2. Check and apply in memory. Every business rejection is thrown here, before any write.
    guard.accept(byId);
    Ledger.Result applied = Ledger.applyWithTrail(posting, locked);

    // 3. Write: transaction, entries, balances, and one outbox event per entry. Commit makes all
    //    of it visible at once — an event can never exist without its entry, or vice versa.
    //    The events are written while the account locks are still held, so for each account the
    //    outbox order matches the commit order (ADR 0010).
    Transaction transaction =
        Transaction.settled(UUID.randomUUID(), tenantId, type, posting.amount());
    transactions.insert(transaction);
    entries.insert(transaction, posting);
    for (Account before : locked) {
      accounts.updateBalance(applied.accounts().get(before.id()), before.version());
    }
    events.record(transaction, applied.trail());
    return transaction;
  }
}
