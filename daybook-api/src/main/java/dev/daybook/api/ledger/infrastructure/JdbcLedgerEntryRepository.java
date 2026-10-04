package dev.daybook.api.ledger.infrastructure;

import dev.daybook.api.ledger.application.LedgerEntryRepository;
import dev.daybook.api.ledger.domain.Entry;
import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.domain.Transaction;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLedgerEntryRepository implements LedgerEntryRepository {

  private final JdbcClient jdbc;

  JdbcLedgerEntryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(Transaction transaction, Posting posting) {
    // Postings are almost always two entries, so a plain loop beats JDBC batching for clarity.
    for (Entry entry : posting.entries()) {
      jdbc.sql(
              """
              INSERT INTO ledger_entries
                  (id, tenant_id, transaction_id, account_id, direction, amount_minor)
              VALUES (:id, :tenantId, :transactionId, :accountId, :direction, :amount)
              """)
          .param("id", UUID.randomUUID())
          .param("tenantId", transaction.tenantId())
          .param("transactionId", transaction.id())
          .param("accountId", entry.accountId())
          .param("direction", entry.direction().name())
          .param("amount", entry.amount().minor())
          .update();
    }
  }
}
