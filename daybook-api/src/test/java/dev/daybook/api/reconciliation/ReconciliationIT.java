package dev.daybook.api.reconciliation;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.reconciliation.application.ReconciliationInProgressException;
import dev.daybook.api.reconciliation.application.ReconciliationService;
import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import dev.daybook.api.reconciliation.domain.ReconciliationReport.Trigger;
import dev.daybook.api.reconciliation.domain.Violation;
import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import dev.daybook.api.support.LedgerTestData;
import dev.daybook.api.support.ProjectionSchema;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation proves the books balance — and, just as important, that it notices when they do
 * not. Each test corrupts the database behind the application's back (as a bug would), then asserts
 * the exact violation is reported and the corruption is left in place: reconciliation never
 * repairs.
 *
 * <p>The shared test database holds every other test's data too, so assertions look only at this
 * test's tenant.
 */
@ApiIntegrationTest
@TestPropertySource(
    properties = {
      "daybook.outbox.relay-enabled=false", // lets a test leave an outbox event unpublished
      "daybook.reconciliation.max-violations-per-check=100000"
    })
class ReconciliationIT {

  @Autowired ReconciliationService reconciliation;
  @Autowired ApiClient api;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired DataSource dataSource;
  @Autowired MeterRegistry meters;
  @Autowired MockMvc mvc;

  @BeforeEach
  void projectionTablesExist() {
    ProjectionSchema.ensureMigrated(dataSource);
  }

  @Test
  void healthyLedgerHasNoViolations() throws Exception {
    Tenant tenant = api.createTenant();
    UUID a = api.createFundedAccount(tenant, 10_000);
    UUID b = api.createAccount(tenant);
    api.transfer(tenant, "t-1", a, b, 2_500);

    ReconciliationReport report = reconciliation.run(Trigger.ON_DEMAND);

    assertThat(report.status()).isEqualTo(ReconciliationReport.Status.COMPLETED);
    assertThat(report.checksRun()).isEqualTo(Check.values().length);
    assertThat(violationsOf(report, tenant)).isEmpty();
  }

  @Test
  void driftedBalanceIsReportedAndLeftUnrepaired() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = ?")
        .param(account)
        .update(); // a "bug" credits 1 paisa without a ledger entry

    ReconciliationReport report = reconciliation.run(Trigger.ON_DEMAND);

    assertThat(violationsOf(report, tenant))
        .extracting(Violation::check, Violation::subjectId)
        .containsExactly(tuple(Check.BALANCE_DRIFT, account));
    assertThat(data.balanceOf(account)).isEqualTo(10_001); // reported, never "fixed"
    assertThat(gauge(Check.BALANCE_DRIFT)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void moneyCreatedPastEveryDefenceIsCaught() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    UUID funding =
        jdbc.sql("SELECT transaction_id FROM ledger_entries WHERE account_id = ?")
            .param(account)
            .query(UUID.class)
            .single();

    // A lone credit with no matching debit. The database's own balance trigger would reject it,
    // so switch triggers off for this one transaction (superuser only) — simulating a bug that
    // got past every defence. The auditor must still catch it.
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL session_replication_role = replica").update();
          jdbc.sql(
                  """
                  INSERT INTO ledger_entries
                      (id, tenant_id, transaction_id, account_id, direction, amount_minor)
                  VALUES (?, ?, ?, ?, 'CREDIT', 500)
                  """)
              .params(UUID.randomUUID(), tenant.id(), funding, account)
              .update();
        });

    List<Check> found = checksOf(reconciliation.run(Trigger.ON_DEMAND), tenant);

    assertThat(found)
        .contains(
            Check.LEDGER_UNBALANCED,
            Check.TRANSACTION_UNBALANCED,
            Check.BALANCE_DRIFT,
            Check.VERSION_DRIFT);
  }

  @Test
  void lostWithdrawalHoldIsAHoldMismatch() throws Exception {
    Tenant tenant = api.createTenant();
    UUID inTransit = data.systemAccountOf(tenant.id(), AccountType.WITHDRAWAL_IN_TRANSIT);
    jdbc.sql("UPDATE accounts SET balance_minor = 700 WHERE id = ?").param(inTransit).update();

    assertThat(checksOf(reconciliation.run(Trigger.ON_DEMAND), tenant))
        .contains(Check.HOLD_MISMATCH);
  }

  @Test
  void stuckPendingTransactionAndLaggingOutboxAreWarnings() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    UUID stuck = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO transactions (id, tenant_id, type, status, amount_minor,
                                      customer_account_id, psp_reference, created_at)
            VALUES (?, ?, 'TOPUP', 'PENDING', 100, ?, ?, now() - interval '1 hour')
            """)
        .params(stuck, tenant.id(), account, stuck.toString())
        .update();
    UUID fakeEvent = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO outbox_events (event_id, event_type, topic, message_key, payload, created_at)
            VALUES (?, 'Test', 'test', 'k', '{}', now() - interval '1 hour')
            """)
        .param(fakeEvent)
        .update();

    ReconciliationReport report;
    try {
      report = reconciliation.run(Trigger.ON_DEMAND);
    } finally {
      // The test database is shared with later classes, whose relay would otherwise try to
      // publish this fake event — and stop at it, blocking every real event behind it.
      jdbc.sql("DELETE FROM outbox_events WHERE event_id = ?").param(fakeEvent).update();
    }

    assertThat(violationsOf(report, tenant))
        .anyMatch(v -> v.check() == Check.STUCK_PENDING && stuck.equals(v.subjectId()));
    assertThat(report.violations()).anyMatch(v -> v.check() == Check.OUTBOX_LAG);
    assertThat(Check.STUCK_PENDING.severity()).isEqualTo(Check.Severity.WARNING);
  }

  @Test
  void projectionDisagreeingWithTheLedgerIsCaughtAndLongLagIsFlagged() throws Exception {
    Tenant tenant = api.createTenant();
    UUID wrong = api.createFundedAccount(tenant, 1_000); // ledger: v1, balance 1000
    UUID lagging = api.createFundedAccount(tenant, 2_000);
    // The consumer applied v1 for `wrong` but recorded the wrong balance.
    jdbc.sql(
            """
            INSERT INTO projection.account_balances
                (account_id, tenant_id, account_type, last_version, balance_minor)
            VALUES (?, ?, 'USER', 1, 999)
            """)
        .params(wrong, tenant.id())
        .update();
    // `lagging` was never projected, and its last entry is long past the staleness threshold.
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL session_replication_role = replica").update();
          jdbc.sql(
                  "UPDATE ledger_entries SET created_at = now() - interval '1 hour'"
                      + " WHERE account_id = ?")
              .param(lagging)
              .update();
        });

    List<Violation> found = violationsOf(reconciliation.run(Trigger.ON_DEMAND), tenant);

    assertThat(found)
        .anyMatch(v -> v.check() == Check.PROJECTION_MISMATCH && wrong.equals(v.subjectId()));
    assertThat(found)
        .anyMatch(v -> v.check() == Check.PROJECTION_STALE && lagging.equals(v.subjectId()));
  }

  @Test
  void onlyOneRunAtATime() throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      jdbc.sql("SELECT pg_advisory_xact_lock(7420002)").query().singleRow();
                      locked.countDown();
                      try {
                        release.await(30, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> reconciliation.run(Trigger.ON_DEMAND))
          .isInstanceOf(ReconciliationInProgressException.class);
      mvc.perform(
              post("/v1/admin/reconciliation/runs")
                  .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY)))
          .andExpect(status().isConflict());
    } finally {
      release.countDown();
      holder.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void runsArePersistedAndServedToAdminsOnly() throws Exception {
    String runId =
        api.read(
                mvc.perform(
                        post("/v1/admin/reconciliation/runs")
                            .header(
                                HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("COMPLETED")))
            .get("id")
            .asString();

    mvc.perform(
            get("/v1/admin/reconciliation/runs")
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(runId));
    mvc.perform(
            get("/v1/admin/reconciliation/runs/{id}", runId)
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.violations").isArray());

    Tenant tenant = api.createTenant();
    mvc.perform(
            post("/v1/admin/reconciliation/runs")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isForbidden());
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private static List<Violation> violationsOf(ReconciliationReport report, Tenant tenant) {
    return report.violations().stream().filter(v -> tenant.id().equals(v.tenantId())).toList();
  }

  private static List<Check> checksOf(ReconciliationReport report, Tenant tenant) {
    return violationsOf(report, tenant).stream().map(Violation::check).toList();
  }

  private double gauge(Check check) {
    return meters
        .get("daybook.reconciliation.violations")
        .tag("check", check.name())
        .gauge()
        .value();
  }
}
