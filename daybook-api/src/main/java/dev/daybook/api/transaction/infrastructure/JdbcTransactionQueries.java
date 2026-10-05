package dev.daybook.api.transaction.infrastructure;

import dev.daybook.api.ledger.domain.Direction;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransactionQueries implements TransactionQueries {

  private final JdbcClient jdbc;

  JdbcTransactionQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<TransactionView> find(UUID tenantId, UUID transactionId) {
    List<EntryView> entries =
        jdbc.sql(
                """
                SELECT account_id, direction, amount_minor
                  FROM ledger_entries
                 WHERE tenant_id = :tenantId AND transaction_id = :id
                 ORDER BY direction DESC, account_id
                """)
            .param("tenantId", tenantId)
            .param("id", transactionId)
            .query(
                (rs, n) ->
                    new EntryView(
                        rs.getObject("account_id", UUID.class),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount_minor")))
            .list();
    return jdbc.sql(
            """
            SELECT id, type, status, amount_minor, created_at
              FROM transactions
             WHERE tenant_id = :tenantId AND id = :id
            """)
        .param("tenantId", tenantId)
        .param("id", transactionId)
        .query(
            (rs, n) ->
                new TransactionView(
                    rs.getObject("id", UUID.class),
                    TransactionType.valueOf(rs.getString("type")),
                    TransactionStatus.valueOf(rs.getString("status")),
                    rs.getLong("amount_minor"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    entries))
        .optional();
  }

  @Override
  public StatementPage statement(
      UUID tenantId, UUID accountId, @Nullable String cursor, int limit) {
    // Keyset pagination: "rows older than the last one I saw", using the
    // (account_id, created_at DESC, id DESC) index. Unlike OFFSET, it stays fast on deep pages and
    // never skips or repeats rows when new entries arrive between page requests.
    String sql =
        """
        SELECT e.id AS entry_id, e.created_at, e.direction, e.amount_minor,
               t.id AS transaction_id, t.type
          FROM ledger_entries e
          JOIN transactions t ON t.id = e.transaction_id
         WHERE e.tenant_id = :tenantId AND e.account_id = :accountId
        """
            + (cursor == null ? "" : " AND (e.created_at, e.id) < (:afterTime, :afterId)")
            + " ORDER BY e.created_at DESC, e.id DESC LIMIT :fetch";

    var spec =
        jdbc.sql(sql)
            .param("tenantId", tenantId)
            .param("accountId", accountId)
            .param("fetch", limit + 1); // one extra row tells us whether another page exists
    if (cursor != null) {
      Cursor after = Cursor.decode(cursor);
      spec =
          spec.param("afterTime", OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC))
              .param("afterId", after.entryId());
    }
    List<Row> rows =
        spec.query(
                (rs, n) ->
                    new Row(
                        rs.getObject("entry_id", UUID.class),
                        new StatementLine(
                            rs.getObject("transaction_id", UUID.class),
                            TransactionType.valueOf(rs.getString("type")),
                            Direction.valueOf(rs.getString("direction")),
                            rs.getLong("amount_minor"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant())))
            .list();

    boolean hasMore = rows.size() > limit;
    List<Row> page = hasMore ? rows.subList(0, limit) : rows;
    String next =
        hasMore
            ? new Cursor(page.getLast().line().createdAt(), page.getLast().entryId()).encode()
            : null;
    return new StatementPage(page.stream().map(Row::line).toList(), next);
  }

  private record Row(UUID entryId, StatementLine line) {}

  /** Position of the last row on a page. Opaque to clients: base64url of "instant|entryId". */
  private record Cursor(Instant createdAt, UUID entryId) {

    String encode() {
      String raw = createdAt + "|" + entryId;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String encoded) {
      try {
        String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        return new Cursor(
            Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
      } catch (RuntimeException e) {
        throw new IllegalArgumentException("Malformed cursor", e);
      }
    }
  }
}
