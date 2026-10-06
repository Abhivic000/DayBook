package dev.daybook.consumer.statement.infrastructure;

import dev.daybook.consumer.statement.application.StatementRepository;
import dev.daybook.consumer.statement.domain.AccountEntryEvent;
import dev.daybook.consumer.statement.domain.ProjectedAccount;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table names are schema-qualified so nothing depends on the connection's search_path. */
@Repository
class JdbcStatementRepository implements StatementRepository {

  private final JdbcClient jdbc;

  JdbcStatementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ProjectedAccount> lockAccount(UUID accountId) {
    return jdbc.sql(
            """
            SELECT last_version, balance_minor
              FROM projection.account_balances
             WHERE account_id = :accountId
               FOR UPDATE
            """)
        .param("accountId", accountId)
        .query(
            (rs, n) ->
                new ProjectedAccount(rs.getLong("last_version"), rs.getLong("balance_minor")))
        .optional();
  }

  @Override
  public void apply(AccountEntryEvent event) {
    jdbc.sql(
            """
            INSERT INTO projection.statement_lines
                (event_id, tenant_id, account_id, account_version, transaction_id,
                 transaction_type, direction, amount_minor, balance_after_minor,
                 occurred_at, correlation_id)
            VALUES (:eventId, :tenantId, :accountId, :version, :transactionId,
                    :transactionType, :direction, :amount, :balanceAfter,
                    :occurredAt, :correlationId)
            """)
        .param("eventId", event.eventId())
        .param("tenantId", event.tenantId())
        .param("accountId", event.accountId())
        .param("version", event.accountVersion())
        .param("transactionId", event.transactionId())
        .param("transactionType", event.transactionType())
        .param("direction", event.direction())
        .param("amount", event.amountMinor())
        .param("balanceAfter", event.balanceAfterMinor())
        .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
        .param("correlationId", event.correlationId())
        .update();

    jdbc.sql(
            """
            INSERT INTO projection.account_balances
                (account_id, tenant_id, account_type, last_version, balance_minor)
            VALUES (:accountId, :tenantId, :accountType, :version, :balance)
            ON CONFLICT (account_id) DO UPDATE
               SET last_version = EXCLUDED.last_version,
                   balance_minor = EXCLUDED.balance_minor,
                   updated_at = now()
            """)
        .param("accountId", event.accountId())
        .param("tenantId", event.tenantId())
        .param("accountType", event.accountType())
        .param("version", event.accountVersion())
        .param("balance", event.balanceAfterMinor())
        .update();
  }
}
