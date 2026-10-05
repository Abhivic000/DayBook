package dev.daybook.api.transaction.application;

import dev.daybook.api.ledger.domain.Direction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read side for transactions and account statements. Every query is tenant-scoped. */
public interface TransactionQueries {

  record EntryView(UUID accountId, Direction direction, long amountMinor) {}

  record TransactionView(
      UUID id,
      TransactionType type,
      TransactionStatus status,
      long amountMinor,
      Instant createdAt,
      List<EntryView> entries) {}

  /** One line of an account statement: this account's side of one transaction. */
  record StatementLine(
      UUID transactionId,
      TransactionType type,
      Direction direction,
      long amountMinor,
      Instant createdAt) {}

  /**
   * One page of a statement.
   *
   * @param nextCursor opaque cursor for the next (older) page, or null if this is the last page
   */
  record StatementPage(List<StatementLine> lines, @Nullable String nextCursor) {}

  Optional<TransactionView> find(UUID tenantId, UUID transactionId);

  /**
   * An account's ledger lines, newest first (FR-2.3).
   *
   * @param cursor from a previous page's {@code nextCursor}, or null for the first page
   * @throws IllegalArgumentException if the cursor is malformed
   */
  StatementPage statement(UUID tenantId, UUID accountId, @Nullable String cursor, int limit);
}
