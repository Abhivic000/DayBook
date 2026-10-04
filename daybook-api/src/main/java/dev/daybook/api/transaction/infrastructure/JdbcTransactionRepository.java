package dev.daybook.api.transaction.infrastructure;

import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
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
            INSERT INTO transactions (id, tenant_id, type, status, amount_minor)
            VALUES (:id, :tenantId, :type, :status, :amount)
            """)
        .param("id", transaction.id())
        .param("tenantId", transaction.tenantId())
        .param("type", transaction.type().name())
        .param("status", transaction.status().name())
        .param("amount", transaction.amount().minor())
        .update();
  }
}
