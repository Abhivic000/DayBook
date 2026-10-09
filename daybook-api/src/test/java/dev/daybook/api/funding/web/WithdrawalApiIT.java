package dev.daybook.api.funding.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import dev.daybook.api.support.LedgerTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Withdrawals (ADR 0006): funds are held before the PSP is asked to pay out, so they can never be
 * spent twice, and a failed payout returns the hold with a compensating REVERSAL.
 */
@ApiIntegrationTest
class WithdrawalApiIT {

  private static final String ERRORS = "https://daybook.dev/errors/";

  @RegisterExtension
  static WireMockExtension psp =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void pointDaybookAtTheFakePsp(DynamicPropertyRegistry registry) {
    registry.add("daybook.psp.base-url", psp::baseUrl);
  }

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;
  @Autowired CircuitBreaker pspCircuitBreaker;

  @BeforeEach
  void closedBreaker() {
    pspCircuitBreaker.reset();
  }

  @Test
  void approvedWithdrawalPaysOutFromTheHold() throws Exception {
    pspAnswers("SUCCEEDED");
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);

    String id =
        api.read(
                withdraw(tenant, "w-1", account, 4_000)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$.status").value("SETTLED"))
                    .andExpect(jsonPath("$.entries.length()").value(4))) // hold + payout legs
            .get("id")
            .asString();

    assertThat(data.balanceOf(account)).isEqualTo(6_000);
    assertThat(inTransit(tenant)).isZero();
    assertThat(data.balanceOf(data.systemAccountOf(tenant.id(), AccountType.PSP_SETTLEMENT)))
        .isEqualTo(4_000);
    psp.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/payments"))
            .withRequestBody(matchingJsonPath("$.reference", equalTo(id)))
            .withRequestBody(matchingJsonPath("$.direction", equalTo("PAYOUT"))));
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void insufficientFundsIs422WithNothingHeldAndThePspNeverCalled() throws Exception {
    pspAnswers("SUCCEEDED");
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 1_000);

    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(ERRORS + "insufficient-funds"));
    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(header().string("Idempotent-Replayed", "true"));

    assertThat(withdrawalStatuses(tenant)).isEmpty();
    assertThat(data.balanceOf(account)).isEqualTo(1_000);
    psp.verify(0, postRequestedFor(urlEqualTo("/v1/payments")));
  }

  @Test
  void declinedPayoutReturnsTheHoldWithAReversal() throws Exception {
    pspAnswers("DECLINED");
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);

    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(ERRORS + "payment-declined"));

    assertThat(data.balanceOf(account)).isEqualTo(10_000);
    assertThat(inTransit(tenant)).isZero();
    assertThat(withdrawalStatuses(tenant)).containsExactly("FAILED");
    assertThat(reversalsOfWithdrawals(tenant)).isEqualTo(1);
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void unknownOutcomeKeepsTheMoneyHeldAndIsNeverRetried() throws Exception {
    psp.stubFor(
        post(urlEqualTo("/v1/payments")).willReturn(payment("SUCCEEDED").withFixedDelay(3_000)));
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);

    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"));

    assertThat(data.balanceOf(account)).isEqualTo(6_000); // held, not spendable
    assertThat(inTransit(tenant)).isEqualTo(4_000);
    assertThat(withdrawalStatuses(tenant)).containsExactly("PENDING");
    psp.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void heldFundsCannotBeSpentWhileThePayoutIsPending() throws Exception {
    // The double-spend ADR 0006 exists to prevent.
    psp.stubFor(
        post(urlEqualTo("/v1/payments")).willReturn(payment("SUCCEEDED").withFixedDelay(3_000)));
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    UUID other = api.createAccount(tenant);

    withdraw(tenant, "w-1", account, 10_000).andExpect(status().isAccepted());

    api.transfer(tenant, "t-1", account, other, 1)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(ERRORS + "insufficient-funds"));
    assertThat(data.balanceOf(account)).isZero();
  }

  @Test
  void concurrentWithdrawalsCanNeverOverdraw() throws Exception {
    pspAnswers("SUCCEEDED");
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    int attempts = 10;

    List<Integer> statuses = new ArrayList<>();
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        String key = "w-" + i;
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return withdraw(tenant, key, account, 2_000)
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      go.countDown();
      for (Future<Integer> f : futures) {
        statuses.add(f.get(60, TimeUnit.SECONDS));
      }
    }

    assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
    assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
    assertThat(data.balanceOf(account)).isZero();
    assertThat(inTransit(tenant)).isZero();
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void pspThatNeverAcceptsReturnsTheHoldAndTheKeyCanBeRetried() throws Exception {
    psp.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(503)));
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);

    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value(ERRORS + "psp-unavailable"));
    assertThat(data.balanceOf(account)).isEqualTo(10_000); // hold returned
    assertThat(reversalsOfWithdrawals(tenant)).isEqualTo(1);

    pspCircuitBreaker.reset();
    psp.resetAll();
    pspAnswers("SUCCEEDED");
    withdraw(tenant, "w-1", account, 4_000).andExpect(status().isCreated()); // key was released
    assertThat(data.balanceOf(account)).isEqualTo(6_000);
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void openBreakerRejectsBeforeAnythingIsHeld() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    pspCircuitBreaker.transitionToOpenState();

    withdraw(tenant, "w-1", account, 4_000)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value(ERRORS + "psp-unavailable"));

    assertThat(data.balanceOf(account)).isEqualTo(10_000);
    assertThat(withdrawalStatuses(tenant)).isEmpty();
    psp.verify(0, postRequestedFor(urlEqualTo("/v1/payments")));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private ResultActions withdraw(Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post("/v1/withdrawals")
            .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"%s\",\"amountMinor\":%d}".formatted(account, amount)));
  }

  private void pspAnswers(String paymentStatus) {
    psp.stubFor(post(urlEqualTo("/v1/payments")).willReturn(payment(paymentStatus)));
  }

  private static ResponseDefinitionBuilder payment(String paymentStatus) {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"reference\":\"r\",\"status\":\"%s\"}".formatted(paymentStatus));
  }

  private long inTransit(Tenant tenant) {
    return data.balanceOf(data.systemAccountOf(tenant.id(), AccountType.WITHDRAWAL_IN_TRANSIT));
  }

  private List<String> withdrawalStatuses(Tenant tenant) {
    return jdbc.sql("SELECT status FROM transactions WHERE tenant_id = ? AND type = 'WITHDRAWAL'")
        .param(tenant.id())
        .query(String.class)
        .list();
  }

  private long reversalsOfWithdrawals(Tenant tenant) {
    return jdbc.sql(
            """
            SELECT count(*) FROM transactions r
              JOIN transactions w ON w.id = r.reverses_transaction_id
             WHERE r.tenant_id = ? AND r.type = 'REVERSAL' AND r.status = 'SETTLED'
               AND w.type = 'WITHDRAWAL' AND w.status = 'FAILED'
            """)
        .param(tenant.id())
        .query(Long.class)
        .single();
  }
}
