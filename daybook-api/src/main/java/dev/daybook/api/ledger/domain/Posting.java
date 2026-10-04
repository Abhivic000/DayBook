package dev.daybook.api.ledger.domain;

import dev.daybook.api.common.domain.Money;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A balanced set of entries written together as part of one transaction. It is impossible to
 * construct an unbalanced posting (INV-1); the database's deferred balance trigger is the backstop
 * if this ever regresses.
 */
public record Posting(List<Entry> entries) {

  public Posting {
    entries = List.copyOf(entries);
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("A posting needs at least one entry");
    }
    Money debits = total(entries, Direction.DEBIT);
    Money credits = total(entries, Direction.CREDIT);
    if (!debits.isPositive() || !credits.isPositive()) {
      throw new IllegalArgumentException("A posting needs at least one debit and one credit");
    }
    if (!debits.equals(credits)) {
      throw new IllegalArgumentException(
          "Unbalanced posting: debits %d, credits %d".formatted(debits.minor(), credits.minor()));
    }
  }

  /** The common two-entry case: move {@code amount} from one account to another. */
  public static Posting move(UUID fromAccountId, UUID toAccountId, Money amount) {
    if (fromAccountId.equals(toAccountId)) {
      throw new IllegalArgumentException("Cannot move money from an account to itself");
    }
    return new Posting(
        List.of(Entry.debit(fromAccountId, amount), Entry.credit(toAccountId, amount)));
  }

  /** The amount moved: total debits, which by construction equals total credits. */
  public Money amount() {
    return total(entries, Direction.DEBIT);
  }

  public Set<UUID> accountIds() {
    return entries.stream().map(Entry::accountId).collect(Collectors.toUnmodifiableSet());
  }

  private static Money total(List<Entry> entries, Direction direction) {
    Money sum = Money.ZERO;
    for (Entry entry : entries) {
      if (entry.direction() == direction) {
        sum = sum.plus(entry.amount());
      }
    }
    return sum;
  }
}
