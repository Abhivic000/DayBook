package dev.daybook.api.funding;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.PendingTransactionSweeper;
import dev.daybook.api.funding.application.PendingTransactionSweeper.SweepReport;
import dev.daybook.api.funding.application.TopUpService;
import dev.daybook.api.idempotency.application.IdempotencyService;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import dev.daybook.api.idempotency.domain.RequestFingerprint;
import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import dev.daybook.api.support.LedgerTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
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
 * The sweeper resolves PENDING top-ups and withdrawals by asking the PSP (FR-6.5, ADR 0016), and
 * never resolves one the PSP cannot vouch for.
 */
@ApiIntegrationTest
class SweeperIT {

  private static final String ERRORS = "https://daybook.dev/errors/";

  @RegisterExtension
  static WireMockExtension psp =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("daybook.psp.base-url", psp::baseUrl);
    registry.add("daybook.sweeper.min-age", () -> "PT0S"); // sweep immediately, not after 60s
  }

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired LedgerTestData data;
  @Autowired JdbcClient jdbc;
  @Autowired CircuitBreaker pspCircuitBreaker;
  @Autowired PendingTransactionSweeper sweeper;
  @Autowired IdempotencyService idempotency;
  @Autowired TopUpService topUps;

  @BeforeEach
  void pspAnswersTooLate() {
    pspCircuitBreaker.reset();
    // Every payment request outlives our read timeout: outcome unknown, transaction PENDING.
    psp.stubFor(
        post(urlEqualTo("/v1/payments")).willReturn(payment("SUCCEEDED").withFixedDelay(3_000)));
  }

  @Test
  void lateTopUpIsSettledOnceThePspConfirmsIt() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    String id = pendingTopUp(tenant, "k-1", account, 50_000);

    pspStatusIs("SUCCEEDED");
    sweeper.sweep();

    assertThat(statusOf(id)).isEqualTo("SETTLED");
    assertThat(data.balanceOf(account)).isEqualTo(50_000);
    // ADR 0017: the key keeps its first answer (202); the transaction shows the outcome.
    topUp(tenant, "k-1", account, 50_000)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void lateWithdrawalThePspDeclinedHasItsHoldReturned() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    String id =
        api.read(withdraw(tenant, "w-1", account, 4_000).andExpect(status().isAccepted()))
            .get("id")
            .asString();
    assertThat(data.balanceOf(account)).isEqualTo(6_000); // held

    pspStatusIs("DECLINED");
    sweeper.sweep();

    assertThat(statusOf(id)).isEqualTo("FAILED");
    assertThat(data.balanceOf(account)).isEqualTo(10_000); // returned by a REVERSAL
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void paymentThePspHasNoRecordOfStaysPending() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    String id =
        api.read(withdraw(tenant, "w-1", account, 4_000).andExpect(status().isAccepted()))
            .get("id")
            .asString();

    psp.stubFor(get(urlPathMatching("/v1/payments/.+")).willReturn(aResponse().withStatus(404)));
    sweeper.sweep();

    assertThat(statusOf(id)).isEqualTo("PENDING"); // "not found" is not "failed": never guessed
    assertThat(data.balanceOf(account)).isEqualTo(6_000); // still held
  }

  @Test
  void unreachablePspLeavesEverythingPending() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    String id = pendingTopUp(tenant, "k-1", account, 700);

    psp.stubFor(get(urlPathMatching("/v1/payments/.+")).willReturn(aResponse().withStatus(503)));
    sweeper.sweep();

    assertThat(statusOf(id)).isEqualTo("PENDING");
  }

  @Test
  void transactionPendingBeyondTheMaximumAgeIsFlaggedButNotResolved() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    String id = pendingTopUp(tenant, "k-1", account, 700);
    jdbc.sql("UPDATE transactions SET created_at = now() - interval '2 days' WHERE id = ?::uuid")
        .param(id)
        .update();

    psp.stubFor(get(urlPathMatching("/v1/payments/.+")).willReturn(aResponse().withStatus(404)));
    SweepReport report = sweeper.sweep();

    assertThat(report.stale()).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(id)).isEqualTo("PENDING");
  }

  @Test
  void keyStrandedByACrashBetweenPhasesGetsItsFinalAnswer() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    TopUpService.TopUpCommand command =
        new TopUpService.TopUpCommand(tenant.id(), account, Money.ofMinor(900));
    String fingerprint =
        RequestFingerprint.of(
            "POST",
            "/v1/topups",
            "{\"accountId\":\"%s\",\"amountMinor\":%d}".formatted(account, 900));

    // Phase one commits, then "the API crashes" before calling the PSP: key stays IN_PROGRESS.
    idempotency.begin(tenant.id(), "k-1", fingerprint, () -> topUps.begin(command), this::reject);
    topUp(tenant, "k-1", account, 900).andExpect(status().isConflict());

    pspStatusIs("SUCCEEDED");
    sweeper.sweep();

    topUp(tenant, "k-1", account, 900)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.status").value("SETTLED"));
    assertThat(data.balanceOf(account)).isEqualTo(900);
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private String pendingTopUp(Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return api.read(topUp(tenant, key, account, amount).andExpect(status().isAccepted()))
        .get("id")
        .asString();
  }

  private ResultActions topUp(Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return send("/v1/topups", tenant, key, account, amount);
  }

  private ResultActions withdraw(Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return send("/v1/withdrawals", tenant, key, account, amount);
  }

  private ResultActions send(String path, Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post(path)
            .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"%s\",\"amountMinor\":%d}".formatted(account, amount)));
  }

  private void pspStatusIs(String paymentStatus) {
    psp.stubFor(get(urlPathMatching("/v1/payments/.+")).willReturn(payment(paymentStatus)));
  }

  private static ResponseDefinitionBuilder payment(String paymentStatus) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"reference\":\"r\",\"status\":\"%s\"}".formatted(paymentStatus));
  }

  private String statusOf(String transactionId) {
    return jdbc.sql("SELECT status FROM transactions WHERE id = ?::uuid")
        .param(transactionId)
        .query(String.class)
        .single();
  }

  private IdempotentResponse reject(DomainException e) {
    return new IdempotentResponse(422, "{\"type\":\"" + ERRORS + e.code() + "\"}", null);
  }
}
