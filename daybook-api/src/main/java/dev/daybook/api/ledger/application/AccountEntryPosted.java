package dev.daybook.api.ledger.application;

import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.ledger.domain.Direction;
import dev.daybook.api.transaction.domain.TransactionType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published once per ledger entry, keyed by {@code accountId} (ADR 0010). Fields may be added in
 * later versions; consumers must ignore unknown fields. Removing or changing a field means a new
 * topic version.
 *
 * @param accountVersion the account's version after this entry: consecutive per account, so a
 *     consumer can detect duplicates, gaps and reordering
 * @param balanceAfterMinor the account's balance after this entry
 */
public record AccountEntryPosted(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    UUID tenantId,
    @Nullable String correlationId,
    UUID accountId,
    AccountType accountType,
    long accountVersion,
    UUID transactionId,
    TransactionType transactionType,
    Direction direction,
    long amountMinor,
    long balanceAfterMinor) {

  public static final String TYPE = "AccountEntryPosted";
}
