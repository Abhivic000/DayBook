package dev.daybook.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Business and health metrics (FR-11.3). The test context shares one registry with every other
 * test, so assertions compare before/after deltas instead of absolute values.
 */
@ApiIntegrationTest
class MetricsIT {

  @Autowired ApiClient api;
  @Autowired MeterRegistry registry;

  @Test
  void aSettledTransferIsCountedOnceWithItsAmount() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10_000);
    UUID to = api.createAccount(tenant);
    double settledBefore = transactions("TRANSFER", "SETTLED");
    long amountsBefore = transferAmounts().count();
    double totalBefore = transferAmounts().totalAmount();

    api.transfer(tenant, "m-1", from, to, 2_500).andExpect(status().isCreated());
    api.transfer(tenant, "m-1", from, to, 2_500).andExpect(status().isCreated()); // replay

    assertThat(transactions("TRANSFER", "SETTLED")).isEqualTo(settledBefore + 1);
    assertThat(transferAmounts().count()).isEqualTo(amountsBefore + 1);
    assertThat(transferAmounts().totalAmount()).isEqualTo(totalBefore + 2_500);
  }

  @Test
  void aRejectedTransferIsNotCounted() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 100);
    UUID to = api.createAccount(tenant);
    double settledBefore = transactions("TRANSFER", "SETTLED");
    long amountsBefore = transferAmounts().count();

    // The ledger rolls the posting back; a metric counted before commit would still show it.
    api.transfer(tenant, "m-2", from, to, 5_000).andExpect(status().isUnprocessableContent());

    assertThat(transactions("TRANSFER", "SETTLED")).isEqualTo(settledBefore);
    assertThat(transferAmounts().count()).isEqualTo(amountsBefore);
  }

  @Test
  void healthGaugesAreRegistered() {
    assertThat(registry.get("daybook.outbox.unpublished").gauge().value()).isNotNaN();
    assertThat(registry.get("daybook.outbox.oldest.unpublished.age").gauge().value())
        .isNotNaN()
        .isGreaterThanOrEqualTo(0);
    // One gauge per state, set to 1 for the current one. Which state is not asserted: other tests
    // sharing this context deliberately trip the breaker.
    assertThat(
            registry.find("resilience4j.circuitbreaker.state").tag("name", "psp").gauges().stream()
                .mapToDouble(g -> g.value())
                .sum())
        .isEqualTo(1);
    assertThat(registry.find("resilience4j.retry.calls").tag("name", "psp").meters()).isNotEmpty();
    assertThat(registry.find("resilience4j.bulkhead.available.concurrent.calls").meters())
        .isNotEmpty();
  }

  private double transactions(String type, String status) {
    Counter counter =
        registry.find("daybook.transactions").tag("type", type).tag("status", status).counter();
    return counter == null ? 0 : counter.count();
  }

  private DistributionSummary transferAmounts() {
    return registry.get("daybook.transfer.amount").summary();
  }
}
