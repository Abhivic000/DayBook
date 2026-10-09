package dev.daybook.api.transaction.infrastructure;

import dev.daybook.api.common.domain.Money;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTransactionRepository implements TransactionRepository {

  private final JdbcClient jdbc;

  JdbcTransactionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Transaction transaction) {
    jdbc.sql(
            """
            INSERT INTO transactions
                (id, tenant_id, type, status, amount_minor, customer_account_id, psp_reference)
            VALUES (:id, :tenantId, :type, :status, :amount, :customerAccountId, :pspReference)
            """)
        .param("id", transaction.id())
        .param("tenantId", transaction.tenantId())
        .param("type", transaction.type().name())
        .param("status", transaction.status().name())
        .param("amount", transaction.amount().minor())
        .param("customerAccountId", transaction.customerAccountId())
        .param("pspReference", transaction.pspReference())
        .update();
  }

  @Override
  public Optional<Transaction> lockForUpdate(UUID tenantId, UUID transactionId) {
    return jdbc.sql(
            """
            SELECT id, tenant_id, type, status, amount_minor, customer_account_id, psp_reference
              FROM transactions
             WHERE tenant_id = :tenantId AND id = :id
               FOR UPDATE
            """)
        .param("tenantId", tenantId)
        .param("id", transactionId)
        .query(
            (rs, n) ->
                new Transaction(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    TransactionType.valueOf(rs.getString("type")),
                    TransactionStatus.valueOf(rs.getString("status")),
                    Money.ofMinor(rs.getLong("amount_minor")),
                    rs.getObject("customer_account_id", UUID.class),
                    rs.getString("psp_reference")))
        .optional();
  }

  @Override
  public void markSettled(UUID transactionId) {
    resolve(transactionId, "SETTLED", null);
  }

  @Override
  public void markFailed(UUID transactionId, String reason) {
    resolve(transactionId, "FAILED", reason);
  }

  private void resolve(UUID transactionId, String status, String reason) {
    int updated =
        jdbc.sql(
                """
                UPDATE transactions
                   SET status = :status, failure_reason = :reason, updated_at = now()
                 WHERE id = :id AND status = 'PENDING'
                """)
            .param("status", status)
            .param("reason", reason)
            .param("id", transactionId)
            .update();
    if (updated != 1) {
      throw new IllegalStateException(
          "Transaction %s is not PENDING; cannot mark it %s".formatted(transactionId, status));
    }
  }
}
