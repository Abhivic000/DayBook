package dev.daybook.api.reconciliation.infrastructure;

import dev.daybook.api.reconciliation.application.ReconciliationChecks;
import dev.daybook.api.reconciliation.application.ReconciliationProperties;
import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.Violation;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Each invariant as one plain aggregate query. Deliberately unclever: the auditor must be simpler
 * than the code it audits, so it re-derives everything from the ledger entries rather than trusting
 * any cached or incremental state. {@code now()} is the snapshot's start time, identical for every
 * check in a run.
 */
@Repository
class JdbcReconciliationChecks implements ReconciliationChecks {

  /** Fixed and distinct from the outbox relay's key: one reconciliation run at a time. */
  static final long RUN_LOCK_KEY = 7_420_002L;

  private static final String SIGNED_FOR_BALANCE =
      "CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END";
  private static final String SIGNED_DEBIT_MINUS_CREDIT =
      "CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END";

  private final JdbcClient jdbc;

  JdbcReconciliationChecks(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean tryLockRun() {
    return jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
        .param("key", RUN_LOCK_KEY)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void limitStatementTime(Duration timeout) {
    // SET does not take bind parameters; the value is a number we computed, not user input.
    jdbc.sql("SET LOCAL statement_timeout = " + timeout.toMillis()).update();
  }

  @Override
  public boolean projectionAvailable() {
    return jdbc.sql("SELECT to_regclass('projection.account_balances') IS NOT NULL")
        .query(Boolean.class)
        .single();
  }

  @Override
  public List<Violation> find(Check check, ReconciliationProperties thresholds, int limit) {
    return switch (check) {
      case LEDGER_UNBALANCED -> ledgerUnbalanced(limit);
      case TRANSACTION_UNBALANCED -> transactionUnbalanced(limit);
      case BALANCE_DRIFT -> balanceDrift(limit);
      case VERSION_DRIFT -> versionDrift(limit);
      case NEGATIVE_BALANCE -> negativeBalance(limit);
      case HOLD_MISMATCH -> holdMismatch(limit);
      case PROJECTION_MISMATCH -> projectionMismatch(limit);
      case PROJECTION_STALE -> projectionStale(thresholds.projectionStaleAfter(), limit);
      case STUCK_PENDING -> stuckPending(thresholds.stuckPendingAfter(), limit);
      case OUTBOX_LAG -> outboxLag(thresholds.outboxLagAfter());
      case PROJECTION_UNAVAILABLE -> List.of(); // decided by the service, not a query
    };
  }

  /** INV-2: per tenant, all debits minus all credits must be exactly zero. */
  private List<Violation> ledgerUnbalanced(int limit) {
    return jdbc.sql(
            "SELECT tenant_id, SUM("
                + SIGNED_DEBIT_MINUS_CREDIT
                + ") AS net FROM ledger_entries GROUP BY tenant_id HAVING SUM("
                + SIGNED_DEBIT_MINUS_CREDIT
                + ") <> 0 LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.LEDGER_UNBALANCED,
                    rs.getObject("tenant_id", UUID.class),
                    null,
                    "Debits minus credits = " + rs.getLong("net") + " (must be 0)"))
        .list();
  }

  /** INV-1: per transaction, debits minus credits must be exactly zero. */
  private List<Violation> transactionUnbalanced(int limit) {
    return jdbc.sql(
            "SELECT tenant_id, transaction_id, SUM("
                + SIGNED_DEBIT_MINUS_CREDIT
                + ") AS net FROM ledger_entries GROUP BY tenant_id, transaction_id HAVING SUM("
                + SIGNED_DEBIT_MINUS_CREDIT
                + ") <> 0 LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.TRANSACTION_UNBALANCED,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("transaction_id", UUID.class),
                    "Debits minus credits = " + rs.getLong("net") + " (must be 0)"))
        .list();
  }

  private static final String ACCOUNTS_WITH_ENTRY_TOTALS =
      """
      SELECT a.id, a.tenant_id, a.type, a.balance_minor, a.version,
             COALESCE(e.balance, 0) AS entries_balance, COALESCE(e.entries, 0) AS entries
        FROM accounts a
        LEFT JOIN (SELECT account_id, SUM(%s) AS balance, COUNT(*) AS entries
                     FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id
      """
          .formatted(SIGNED_FOR_BALANCE);

  /** INV-3: the cached balance must equal the sum of the account's entries. */
  private List<Violation> balanceDrift(int limit) {
    return jdbc.sql(
            ACCOUNTS_WITH_ENTRY_TOTALS
                + " WHERE a.balance_minor <> COALESCE(e.balance, 0) LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.BALANCE_DRIFT,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "%s account: cached balance %d, entries sum to %d"
                        .formatted(
                            rs.getString("type"),
                            rs.getLong("balance_minor"),
                            rs.getLong("entries_balance"))))
        .list();
  }

  /** Every entry increments its account's version once, so version = number of entries. */
  private List<Violation> versionDrift(int limit) {
    return jdbc.sql(
            ACCOUNTS_WITH_ENTRY_TOTALS + " WHERE a.version <> COALESCE(e.entries, 0) LIMIT :limit")
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.VERSION_DRIFT,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "%s account: version %d, but %d entries"
                        .formatted(
                            rs.getString("type"), rs.getLong("version"), rs.getLong("entries"))))
        .list();
  }

  /** INV-6, and holds: these accounts can never legitimately be negative. */
  private List<Violation> negativeBalance(int limit) {
    return jdbc.sql(
            """
            SELECT id, tenant_id, type, balance_minor FROM accounts
             WHERE balance_minor < 0 AND type IN ('USER', 'WITHDRAWAL_IN_TRANSIT')
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.NEGATIVE_BALANCE,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "%s account balance %d"
                        .formatted(rs.getString("type"), rs.getLong("balance_minor"))))
        .list();
  }

  /** ADR 0006: in-transit holds exactly the money of the withdrawals still in flight. */
  private List<Violation> holdMismatch(int limit) {
    return jdbc.sql(
            """
            SELECT a.tenant_id, a.id, a.balance_minor, COALESCE(w.held, 0) AS held
              FROM accounts a
              LEFT JOIN (SELECT tenant_id, SUM(amount_minor) AS held FROM transactions
                          WHERE type = 'WITHDRAWAL' AND status = 'PENDING'
                          GROUP BY tenant_id) w ON w.tenant_id = a.tenant_id
             WHERE a.type = 'WITHDRAWAL_IN_TRANSIT' AND a.balance_minor <> COALESCE(w.held, 0)
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.HOLD_MISMATCH,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "In-transit balance %d, but PENDING withdrawals total %d"
                        .formatted(rs.getLong("balance_minor"), rs.getLong("held"))))
        .list();
  }

  /**
   * INV-7 where comparable: once the projection has applied an account's latest version, its
   * balance must equal the ledger's. A projection ahead of the ledger is impossible.
   */
  private List<Violation> projectionMismatch(int limit) {
    return jdbc.sql(
            """
            SELECT a.id, a.tenant_id, a.version, a.balance_minor,
                   p.last_version, p.balance_minor AS projected_balance
              FROM accounts a
              JOIN projection.account_balances p ON p.account_id = a.id
             WHERE (p.last_version = a.version AND p.balance_minor <> a.balance_minor)
                OR p.last_version > a.version
             LIMIT :limit
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.PROJECTION_MISMATCH,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "Ledger v%d balance %d; projection v%d balance %d"
                        .formatted(
                            rs.getLong("version"),
                            rs.getLong("balance_minor"),
                            rs.getLong("last_version"),
                            rs.getLong("projected_balance"))))
        .list();
  }

  /** Behind is only lag — unless still behind long after the account's last ledger entry. */
  private List<Violation> projectionStale(Duration after, int limit) {
    return jdbc.sql(
            """
            SELECT a.id, a.tenant_id, a.version, COALESCE(p.last_version, 0) AS projected,
                   last.at AS last_entry_at
              FROM accounts a
              CROSS JOIN LATERAL (SELECT max(e.created_at) AS at FROM ledger_entries e
                                   WHERE e.account_id = a.id) last
              LEFT JOIN projection.account_balances p ON p.account_id = a.id
             WHERE a.version > COALESCE(p.last_version, 0)
               AND last.at < now() - make_interval(secs => :seconds)
             LIMIT :limit
            """)
        .param("seconds", after.toSeconds())
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.PROJECTION_STALE,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "Projection at v%d, ledger at v%d since %s"
                        .formatted(
                            rs.getLong("projected"),
                            rs.getLong("version"),
                            rs.getObject("last_entry_at", OffsetDateTime.class))))
        .list();
  }

  /** FR-10.2: PENDING longer than the threshold — the PSP has not confirmed an outcome. */
  private List<Violation> stuckPending(Duration after, int limit) {
    return jdbc.sql(
            """
            SELECT id, tenant_id, type, created_at FROM transactions
             WHERE status = 'PENDING' AND created_at < now() - make_interval(secs => :seconds)
             ORDER BY created_at
             LIMIT :limit
            """)
        .param("seconds", after.toSeconds())
        .param("limit", limit)
        .query(
            (rs, n) ->
                new Violation(
                    Check.STUCK_PENDING,
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    "%s PENDING since %s"
                        .formatted(
                            rs.getString("type"),
                            rs.getObject("created_at", OffsetDateTime.class))))
        .list();
  }

  /** FR-10.2: one aggregate violation, since lagging events share one cause (relay or Kafka). */
  private List<Violation> outboxLag(Duration after) {
    return jdbc
        .sql(
            """
            SELECT count(*) AS lagging, min(created_at) AS oldest FROM outbox_events
             WHERE published_at IS NULL AND created_at < now() - make_interval(secs => :seconds)
            """)
        .param("seconds", after.toSeconds())
        .query(
            (rs, n) ->
                rs.getLong("lagging") == 0
                    ? null
                    : new Violation(
                        Check.OUTBOX_LAG,
                        null,
                        null,
                        "%d events unpublished, oldest from %s"
                            .formatted(
                                rs.getLong("lagging"),
                                rs.getObject("oldest", OffsetDateTime.class))))
        .list()
        .stream()
        .filter(Objects::nonNull)
        .toList();
  }
}
