package dev.daybook.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that the core invariants are enforced by the database itself, independent of any
 * application code.
 *
 * <p>Tests commit for real (no rollback-per-test): the balance check is a deferred trigger that
 * only fires at COMMIT, and ledger rows cannot be deleted for cleanup anyway. Each test creates its
 * own tenant, so tests never see each other's data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaConstraintsIT {

  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  // --- INV-1: balanced transactions -------------------------------------------------------------

  @Test
  void balancedTransactionCommits() {
    UUID tenant = newTenant();
    UUID from = newAccount(tenant, "TREASURY", true);
    UUID to = newAccount(tenant, "USER", false);

    assertThatCode(() -> postTransfer(tenant, from, to, 500)).doesNotThrowAnyException();
  }

  @Test
  void unbalancedTransactionIsRejectedAtCommit() {
    UUID tenant = newTenant();
    UUID from = newAccount(tenant, "TREASURY", true);
    UUID to = newAccount(tenant, "USER", false);

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      UUID txn = newTransaction(tenant, 500);
                      insertEntry(tenant, txn, from, "DEBIT", 500);
                      insertEntry(tenant, txn, to, "CREDIT", 499);
                    }))
        .rootCause()
        .hasMessageContaining("is unbalanced");
  }

  // --- INV-4: append-only ledger ----------------------------------------------------------------

  @Test
  void ledgerEntryCannotBeUpdated() {
    UUID entry = committedEntry();

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?")
                    .param(entry)
                    .update())
        .rootCause()
        .hasMessageContaining("append-only");
  }

  @Test
  void ledgerEntryCannotBeDeleted() {
    UUID entry = committedEntry();

    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM ledger_entries WHERE id = ?").param(entry).update())
        .rootCause()
        .hasMessageContaining("append-only");
  }

  @Test
  void ledgerEntriesCannotBeTruncated() {
    committedEntry();

    assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_entries").update())
        .rootCause()
        .hasMessageContaining("append-only");
  }

  // --- Tenant isolation at the database level ---------------------------------------------------

  @Test
  void entryCannotReferenceAnotherTenantsAccount() {
    UUID tenantA = newTenant();
    UUID tenantB = newTenant();
    UUID accountOfA = newAccount(tenantA, "USER", false);
    UUID txnOfB = newTransaction(tenantB, 100);

    assertThatThrownBy(() -> insertEntry(tenantB, txnOfB, accountOfA, "CREDIT", 100))
        .rootCause()
        .hasMessageContaining("ledger_entries_account_fk");
  }

  // --- INV-6 and account rules ------------------------------------------------------------------

  @Test
  void userBalanceCannotGoNegative() {
    UUID tenant = newTenant();
    UUID user = newAccount(tenant, "USER", false);

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE accounts SET balance_minor = -1 WHERE id = ?")
                    .param(user)
                    .update())
        .rootCause()
        .hasMessageContaining("accounts_balance_check");
  }

  @Test
  void userAccountCannotBeConfiguredToAllowNegative() {
    UUID tenant = newTenant();

    assertThatThrownBy(() -> newAccount(tenant, "USER", true))
        .rootCause()
        .hasMessageContaining("accounts_user_not_negative_check");
  }

  @Test
  void tenantHasAtMostOneTreasuryAccount() {
    UUID tenant = newTenant();
    newAccount(tenant, "TREASURY", true);

    assertThatThrownBy(() -> newAccount(tenant, "TREASURY", true))
        .rootCause()
        .hasMessageContaining("accounts_one_system_account_per_type_idx");
  }

  // --- INV-5: idempotency key uniqueness --------------------------------------------------------

  @Test
  void idempotencyKeyIsUniquePerTenantButIndependentAcrossTenants() {
    UUID tenantA = newTenant();
    UUID tenantB = newTenant();
    insertIdempotencyKey(tenantA, "key-1");

    assertThatThrownBy(() -> insertIdempotencyKey(tenantA, "key-1"))
        .rootCause()
        .hasMessageContaining("idempotency_keys_tenant_key_key");
    assertThatCode(() -> insertIdempotencyKey(tenantB, "key-1")).doesNotThrowAnyException();
  }

  @Test
  void migrationsCreatedEveryTable() {
    var tables =
        jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
            .query(String.class)
            .list();

    assertThat(tables)
        .contains("tenants", "api_keys", "accounts", "transactions", "ledger_entries")
        .contains("idempotency_keys");
  }

  // --- Fixtures ---------------------------------------------------------------------------------

  private UUID newTenant() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO tenants (id, name) VALUES (?, ?)").params(id, "tenant-" + id).update();
    return id;
  }

  private UUID newAccount(UUID tenant, String type, boolean allowNegative) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO accounts (id, tenant_id, type, allow_negative, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """)
        .params(id, tenant, type, allowNegative)
        .update();
    return id;
  }

  private UUID newTransaction(UUID tenant, long amount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO transactions (id, tenant_id, type, status, amount_minor)
            VALUES (?, ?, 'TRANSFER', 'SETTLED', ?)
            """)
        .params(id, tenant, amount)
        .update();
    return id;
  }

  private UUID insertEntry(UUID tenant, UUID txn, UUID account, String direction, long amount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO ledger_entries (id, tenant_id, transaction_id, account_id, direction, amount_minor)
            VALUES (?, ?, ?, ?, ?, ?)
            """)
        .params(id, tenant, txn, account, direction, amount)
        .update();
    return id;
  }

  /** Posts a balanced two-entry transaction in one committed DB transaction; returns the debit. */
  private UUID postTransfer(UUID tenant, UUID from, UUID to, long amount) {
    return tx.execute(
        s -> {
          UUID txn = newTransaction(tenant, amount);
          UUID debit = insertEntry(tenant, txn, from, "DEBIT", amount);
          insertEntry(tenant, txn, to, "CREDIT", amount);
          return debit;
        });
  }

  private UUID committedEntry() {
    UUID tenant = newTenant();
    return postTransfer(
        tenant, newAccount(tenant, "TREASURY", true), newAccount(tenant, "USER", false), 100);
  }

  private void insertIdempotencyKey(UUID tenant, String key) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys (id, tenant_id, idempotency_key, request_hash, status, expires_at)
            VALUES (?, ?, ?, 'hash', 'IN_PROGRESS', ?)
            """)
        .params(UUID.randomUUID(), tenant, key, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
        .update();
  }
}
