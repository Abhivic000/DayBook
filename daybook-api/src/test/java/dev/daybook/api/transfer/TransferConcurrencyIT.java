package dev.daybook.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.daybook.api.TestcontainersConfiguration;
import dev.daybook.api.account.domain.InsufficientFundsException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.support.LedgerTestData;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * NFR-3: correctness with at least 50 concurrent writers on one account.
 *
 * <p>The connection pool is raised above the writer count so all 50 really are inside the database
 * contending for the same row lock at once. With the default pool of 10, at most 10 could be, and
 * the test would be weaker than it looks.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=60")
@Import(TestcontainersConfiguration.class)
class TransferConcurrencyIT {

  private static final int WRITERS = 50;

  @Autowired TransferService transfers;
  @Autowired LedgerTestData data;

  enum Outcome {
    OK,
    INSUFFICIENT_FUNDS
  }

  @Test
  void concurrentDebitsOfOneAccountLoseNoUpdates() throws Exception {
    UUID tenant = data.newTenant();
    UUID hot = data.newFundedUserAccount(tenant, 1_000_000);
    List<UUID> destinations = new ArrayList<>();
    for (int i = 0; i < WRITERS; i++) {
      destinations.add(data.newUserAccount(tenant));
    }

    List<Outcome> outcomes =
        runConcurrently(
            WRITERS,
            i ->
                transfers.transfer(
                    new TransferCommand(tenant, hot, destinations.get(i), Money.ofMinor(100))));

    assertThat(outcomes).containsOnly(Outcome.OK);
    assertThat(data.balanceOf(hot)).isEqualTo(1_000_000 - WRITERS * 100);
    assertThat(data.entryCount(hot)).isEqualTo(1 + WRITERS); // funding credit + 50 debits
    data.assertLedgerConsistent(tenant);
  }

  @Test
  void concurrentOverdraftAttemptsCannotDoubleSpend() throws Exception {
    // 30 units of money, 50 writers each trying to take 1 unit: exactly 30 may succeed.
    UUID tenant = data.newTenant();
    UUID hot = data.newFundedUserAccount(tenant, 30);
    UUID sink = data.newUserAccount(tenant);

    List<Outcome> outcomes =
        runConcurrently(
            WRITERS,
            i -> transfers.transfer(new TransferCommand(tenant, hot, sink, Money.ofMinor(1))));

    assertThat(outcomes).filteredOn(o -> o == Outcome.OK).hasSize(30);
    assertThat(outcomes).filteredOn(o -> o == Outcome.INSUFFICIENT_FUNDS).hasSize(20);
    assertThat(data.balanceOf(hot)).isZero();
    assertThat(data.balanceOf(sink)).isEqualTo(30);
    data.assertLedgerConsistent(tenant);
  }

  @Test
  void opposingTransfersDoNotDeadlock() throws Exception {
    // Half the writers move A->B, half B->A. Without a consistent lock order, Postgres would
    // detect deadlocks and abort some of them; with ascending-id ordering they simply queue.
    UUID tenant = data.newTenant();
    UUID a = data.newFundedUserAccount(tenant, 100_000);
    UUID b = data.newFundedUserAccount(tenant, 100_000);

    List<Outcome> outcomes =
        runConcurrently(
            WRITERS,
            i ->
                i % 2 == 0
                    ? transfers.transfer(new TransferCommand(tenant, a, b, Money.ofMinor(7)))
                    : transfers.transfer(new TransferCommand(tenant, b, a, Money.ofMinor(7))));

    assertThat(outcomes).containsOnly(Outcome.OK);
    assertThat(data.balanceOf(a)).isEqualTo(100_000); // 25 out, 25 in
    assertThat(data.balanceOf(b)).isEqualTo(100_000);
    data.assertLedgerConsistent(tenant);
  }

  interface Task {
    Object run(int index) throws Exception;
  }

  /**
   * Starts {@code count} tasks on separate threads, releases them at the same instant, and returns
   * each one's outcome. Any exception other than insufficient funds fails the test.
   */
  private static List<Outcome> runConcurrently(int count, Task task) throws Exception {
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Outcome>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
      for (int i = 0; i < count; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  try {
                    task.run(index);
                    return Outcome.OK;
                  } catch (InsufficientFundsException e) {
                    return Outcome.INSUFFICIENT_FUNDS;
                  }
                }));
      }
      ready.await();
      go.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> future : futures) {
        outcomes.add(future.get(60, TimeUnit.SECONDS)); // rethrows any unexpected failure
      }
      return outcomes;
    }
  }
}
