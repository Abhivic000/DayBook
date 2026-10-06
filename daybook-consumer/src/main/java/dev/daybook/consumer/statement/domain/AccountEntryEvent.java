package dev.daybook.consumer.statement.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The consumer's own view of an {@code AccountEntryPosted} event (ADR 0010).
 *
 * <p>Deliberately not shared with the producer as a class: the consumer declares only the fields it
 * needs and ignores the rest ("tolerant reader"), so the producer can add fields without a
 * coordinated release. Construction validates the fields this consumer depends on; an invalid event
 * fails deserialisation and is dead-lettered without retries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountEntryEvent(
    UUID eventId,
    UUID tenantId,
    @Nullable String correlationId,
    UUID accountId,
    String accountType,
    long accountVersion,
    UUID transactionId,
    String transactionType,
    String direction,
    long amountMinor,
    long balanceAfterMinor,
    Instant occurredAt) {

  public static final String DEBIT = "DEBIT";
  public static final String CREDIT = "CREDIT";

  public AccountEntryEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(accountType, "accountType");
    Objects.requireNonNull(transactionId, "transactionId");
    Objects.requireNonNull(transactionType, "transactionType");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (!DEBIT.equals(direction) && !CREDIT.equals(direction)) {
      throw new IllegalArgumentException("direction must be DEBIT or CREDIT, got " + direction);
    }
    if (accountVersion < 1) {
      throw new IllegalArgumentException("accountVersion must be >= 1, got " + accountVersion);
    }
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive, got " + amountMinor);
    }
  }

  /** The entry's effect on the balance: credits add, debits subtract (INV-3 sign convention). */
  public long signedAmountMinor() {
    return CREDIT.equals(direction) ? amountMinor : -amountMinor;
  }
}
