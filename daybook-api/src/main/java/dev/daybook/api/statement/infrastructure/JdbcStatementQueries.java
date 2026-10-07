package dev.daybook.api.statement.infrastructure;

import dev.daybook.api.statement.application.StatementQueries;
import dev.daybook.api.statement.application.StatementUnavailableException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the projection tables owned by the consumer. They are a contract between the two services:
 * the API's tests create them from the consumer's own migration files to keep it honest.
 */
@Repository
class JdbcStatementQueries implements StatementQueries {

  private final JdbcClient jdbc;

  JdbcStatementQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ProjectedPage page(
      UUID tenantId, UUID accountId, @Nullable Long beforeVersion, int limit) {
    try {
      long projectedThrough =
          jdbc.sql(
                  """
                  SELECT COALESCE(
                      (SELECT last_version FROM projection.account_balances
                        WHERE tenant_id = :tenantId AND account_id = :accountId), 0)
                  """)
              .param("tenantId", tenantId)
              .param("accountId", accountId)
              .query(Long.class)
              .single();

      // Versions are unique and consecutive per account, so the version is a simple, stable
      // keyset cursor (served by the (account_id, account_version) unique index).
      String sql =
          """
          SELECT account_version, transaction_id, transaction_type, direction,
                 amount_minor, balance_after_minor, occurred_at
            FROM projection.statement_lines
           WHERE tenant_id = :tenantId AND account_id = :accountId
          """
              + (beforeVersion == null ? "" : " AND account_version < :before")
              + " ORDER BY account_version DESC LIMIT :fetch";
      var query =
          jdbc.sql(sql)
              .param("tenantId", tenantId)
              .param("accountId", accountId)
              .param("fetch", limit + 1);
      if (beforeVersion != null) {
        query = query.param("before", beforeVersion);
      }
      List<StatementLine> rows =
          query
              .query(
                  (rs, n) ->
                      new StatementLine(
                          rs.getLong("account_version"),
                          rs.getObject("transaction_id", UUID.class),
                          rs.getString("transaction_type"),
                          rs.getString("direction"),
                          rs.getLong("amount_minor"),
                          rs.getLong("balance_after_minor"),
                          rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
              .list();

      boolean hasMore = rows.size() > limit;
      return new ProjectedPage(hasMore ? rows.subList(0, limit) : rows, hasMore, projectedThrough);
    } catch (BadSqlGrammarException e) {
      // Typically "relation projection.statement_lines does not exist": consumer not deployed yet.
      throw new StatementUnavailableException(e);
    }
  }
}
