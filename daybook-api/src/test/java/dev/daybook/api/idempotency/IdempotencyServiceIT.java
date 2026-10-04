package dev.daybook.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.daybook.api.TestcontainersConfiguration;
import dev.daybook.api.account.domain.AccountNotFoundException;
import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.idempotency.application.IdempotencyPurgeJob;
import dev.daybook.api.idempotency.application.IdempotencyService;
import dev.daybook.api.idempotency.application.IdempotentResult;
import dev.daybook.api.idempotency.domain.IdempotencyKeyReusedException;
import dev.daybook.api.idempotency.domain.IdempotencyRequestInProgressException;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import dev.daybook.api.support.LedgerTestData;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transfer.application.TransferCommand;
import dev.daybook.api.transfer.application.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=30")
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceIT {

  private static final String HASH = "hash-a";

  @Autowired IdempotencyService idempotency;
  @Autowired IdempotencyPurgeJob purgeJob;
  @Autowired TransferService transfers;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;

  // --- FR-5.3 / FR-5.4: first use runs, replay returns the stored answer -----------------------

  @Test
  void firstRequestRunsTheActionAndReplayReturnsTheStoredResponse() {
    Scenario s = scenario(1_000);

    IdempotentResult first =
        idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);
    IdempotentResult replay =
        idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);

    assertThat(first.replayed()).isFalse();
    assertThat(first.response().status()).isEqualTo(201);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(first.response());
    assertThat(data.balanceOf(s.from)).isEqualTo(900); // moved once, not twice
    data.assertLedgerConsistent(s.tenant);
  }

  // --- FR-5.5: same key, different request ------------------------------------------------------

  @Test
  void sameKeyWithDifferentRequestIsRejectedAndNothingRuns() {
    Scenario s = scenario(1_000);
    idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    s.tenant, "key-1", "hash-b", s.transfer(200), IdempotencyServiceIT::reject))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(data.balanceOf(s.from)).isEqualTo(900);
  }

  // --- FR-5.2: keys are per tenant -------------------------------------------------------------

  @Test
  void sameKeyUnderDifferentTenantsIsIndependent() {
    Scenario a = scenario(1_000);
    Scenario b = scenario(1_000);

    IdempotentResult forA =
        idempotency.execute(
            a.tenant, "shared", HASH, a.transfer(100), IdempotencyServiceIT::reject);
    IdempotentResult forB =
        idempotency.execute(
            b.tenant, "shared", HASH, b.transfer(100), IdempotencyServiceIT::reject);

    assertThat(forA.replayed()).isFalse();
    assertThat(forB.replayed()).isFalse();
    assertThat(data.balanceOf(a.from)).isEqualTo(900);
    assertThat(data.balanceOf(b.from)).isEqualTo(900);
  }

  // --- ADR 0007: business rejections are final answers -----------------------------------------

  @Test
  void businessRejectionIsStoredAndReplayedEvenAfterFundsArrive() {
    Scenario s = scenario(50);

    IdempotentResult rejected =
        idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);
    data.fund(s.tenant, s.from, 1_000); // now the transfer *could* succeed...
    IdempotentResult replay =
        idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);

    assertThat(rejected.response().status()).isEqualTo(422);
    assertThat(rejected.response().body()).contains("insufficient-funds");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response())
        .isEqualTo(rejected.response()); // ...but the key's answer is final
    assertThat(data.balanceOf(s.from)).isEqualTo(1_050);
    data.assertLedgerConsistent(s.tenant);
  }

  @Test
  void businessRejectionUndoesAnythingTheActionWroteBeforeFailing() {
    Scenario s = scenario(0);
    UUID strayTenant = UUID.randomUUID();

    IdempotentResult result =
        idempotency.execute(
            s.tenant,
            "key-1",
            HASH,
            () -> {
              jdbc.sql("INSERT INTO tenants (id, name) VALUES (?, 'stray')")
                  .param(strayTenant)
                  .update();
              throw new TestRejection();
            },
            IdempotencyServiceIT::reject);

    assertThat(result.response().status()).isEqualTo(422);
    assertThat(storedStatus(s.tenant, "key-1")).isEqualTo("COMPLETED");
    assertThat(
            jdbc.sql("SELECT count(*) FROM tenants WHERE id = ?")
                .param(strayTenant)
                .query(Long.class)
                .single())
        .as("write made before the rejection is rolled back to the savepoint")
        .isZero();
  }

  // --- Non-business failures roll back the key, so a retry runs again --------------------------

  @Test
  void unexpectedFailureReleasesTheKeyForRetry() {
    Scenario s = scenario(1_000);

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    s.tenant,
                    "key-1",
                    HASH,
                    () -> {
                      throw new IllegalStateException("simulated crash");
                    },
                    IdempotencyServiceIT::reject))
        .isInstanceOf(IllegalStateException.class);
    assertThat(storedStatus(s.tenant, "key-1")).isNull();

    IdempotentResult retry =
        idempotency.execute(s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject);
    assertThat(retry.replayed()).isFalse();
    assertThat(data.balanceOf(s.from)).isEqualTo(900);
  }

  @Test
  void notFoundIsNotStored() {
    Scenario s = scenario(1_000);
    TransferCommand toNowhere =
        new TransferCommand(s.tenant, s.from, UUID.randomUUID(), Money.ofMinor(100));

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    s.tenant,
                    "key-1",
                    HASH,
                    transferResponse(() -> transfers.transfer(toNowhere)),
                    IdempotencyServiceIT::reject))
        .isInstanceOf(AccountNotFoundException.class);
    assertThat(storedStatus(s.tenant, "key-1")).isNull();
  }

  // --- FR-5.6: in flight -------------------------------------------------------------------------

  @Test
  void keyStillInProgressIsReportedAsInFlight() {
    Scenario s = scenario(1_000);
    jdbc.sql(
            """
            INSERT INTO idempotency_keys
                (id, tenant_id, idempotency_key, request_hash, status, expires_at)
            VALUES (?, ?, 'key-1', ?, 'IN_PROGRESS', now() + interval '1 hour')
            """)
        .params(UUID.randomUUID(), s.tenant, HASH)
        .update();

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    s.tenant, "key-1", HASH, s.transfer(100), IdempotencyServiceIT::reject))
        .isInstanceOf(IdempotencyRequestInProgressException.class);
    assertThat(data.balanceOf(s.from)).isEqualTo(1_000);
  }

  // --- INV-5 under concurrency -------------------------------------------------------------------

  @Test
  void concurrentRequestsWithTheSameKeyApplyTheEffectExactlyOnce() throws Exception {
    Scenario s = scenario(1_000);
    int threads = 20;
    AtomicInteger actionRuns = new AtomicInteger();
    Supplier<IdempotentResponse> countedTransfer =
        () -> {
          actionRuns.incrementAndGet();
          return s.transfer(100).get();
        };

    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<IdempotentResult>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return idempotency.execute(
                      s.tenant, "same-key", HASH, countedTransfer, IdempotencyServiceIT::reject);
                }));
      }
      ready.await();
      go.countDown();
      List<IdempotentResult> results = new ArrayList<>();
      for (Future<IdempotentResult> f : futures) {
        results.add(f.get(60, TimeUnit.SECONDS));
      }

      assertThat(actionRuns).hasValue(1);
      assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
      assertThat(results)
          .extracting(IdempotentResult::response)
          .containsOnly(results.get(0).response());
    }
    assertThat(data.balanceOf(s.from)).isEqualTo(900);
    data.assertLedgerConsistent(s.tenant);
  }

  // --- FR-5.7: purge -----------------------------------------------------------------------------

  @Test
  void purgeDeletesOnlyExpiredKeys() {
    Scenario s = scenario(1_000);
    idempotency.execute(s.tenant, "old", HASH, s.transfer(100), IdempotencyServiceIT::reject);
    idempotency.execute(s.tenant, "fresh", HASH, s.transfer(100), IdempotencyServiceIT::reject);
    jdbc.sql(
            "UPDATE idempotency_keys SET expires_at = now() - interval '1 minute'"
                + " WHERE tenant_id = ? AND idempotency_key = 'old'")
        .param(s.tenant)
        .update();

    assertThat(purgeJob.purgeExpired()).isGreaterThanOrEqualTo(1);
    assertThat(storedStatus(s.tenant, "old")).isNull();
    assertThat(storedStatus(s.tenant, "fresh")).isEqualTo("COMPLETED");
  }

  // --- Fixtures ----------------------------------------------------------------------------------

  /** Stand-in for the web layer's mapping of a business rejection to a 422 problem response. */
  private static IdempotentResponse reject(DomainException e) {
    return new IdempotentResponse(422, "{\"type\":\"" + e.code() + "\"}", null);
  }

  private Scenario scenario(long openingBalance) {
    UUID tenant = data.newTenant();
    UUID from =
        openingBalance > 0
            ? data.newFundedUserAccount(tenant, openingBalance)
            : data.newUserAccount(tenant);
    return new Scenario(tenant, from, data.newUserAccount(tenant));
  }

  /** A tenant with a source and destination account. Inner (non-static) to reach the services. */
  private final class Scenario {
    final UUID tenant;
    final UUID from;
    final UUID to;

    Scenario(UUID tenant, UUID from, UUID to) {
      this.tenant = tenant;
      this.from = from;
      this.to = to;
    }

    /** An idempotent action that transfers {@code amount} from {@code from} to {@code to}. */
    Supplier<IdempotentResponse> transfer(long amount) {
      return transferResponse(
          () -> transfers.transfer(new TransferCommand(tenant, from, to, Money.ofMinor(amount))));
    }
  }

  private Supplier<IdempotentResponse> transferResponse(Supplier<Transaction> transfer) {
    return () -> {
      Transaction txn = transfer.get();
      return new IdempotentResponse(201, "{\"transactionId\":\"" + txn.id() + "\"}", txn.id());
    };
  }

  private String storedStatus(UUID tenant, String key) {
    return jdbc.sql(
            "SELECT status FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?")
        .params(tenant, key)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static final class TestRejection extends DomainException {
    TestRejection() {
      super("rejected for test");
    }

    @Override
    public String code() {
      return "test-rejection";
    }
  }
}
