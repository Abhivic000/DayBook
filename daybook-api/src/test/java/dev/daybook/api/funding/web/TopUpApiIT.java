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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import dev.daybook.api.support.LedgerTestData;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
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
import tools.jackson.databind.JsonNode;

/**
 * Top-ups against a fake PSP (WireMock), one exact PSP behaviour per test. The central rule under
 * test: a PSP outcome is never guessed — only a provably-not-accepted request may fail, and a
 * request whose fate is unknown is never retried and stays PENDING.
 */
@ApiIntegrationTest
class TopUpApiIT {

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
    pspCircuitBreaker.reset(); // state is shared across tests in one Spring context
  }

  @Test
  void approvedTopUpSettlesAndCreditsTheAccount() throws Exception {
    pspAnswers(201, "SUCCEEDED");
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    JsonNode body =
        api.read(
            topUp(tenant, "k-1", account, 50_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TOPUP"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.entries.length()").value(2)));

    String transactionId = body.get("id").asString();
    assertThat(balance(tenant, account)).isEqualTo(50_000);
    assertThat(data.balanceOf(data.systemAccountOf(tenant.id(), AccountType.PSP_SETTLEMENT)))
        .isEqualTo(-50_000);
    psp.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/payments"))
            .withRequestBody(matchingJsonPath("$.reference", equalTo(transactionId)))
            .withRequestBody(matchingJsonPath("$.direction", equalTo("COLLECT"))));
    data.assertLedgerConsistent(tenant.id());
  }

  @Test
  void declinedTopUpIs422StoredAndReplayedWithNothingPosted() throws Exception {
    pspAnswers(201, "DECLINED");
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    String first =
        topUp(tenant, "k-1", account, 50_000)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.type").value(ERRORS + "payment-declined"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        topUp(tenant, "k-1", account, 50_000)
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(replay).isEqualTo(first);
    assertThat(balance(tenant, account)).isZero();
    assertThat(topUpStatuses(tenant)).containsExactly("FAILED");
    psp.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
  }

  @Test
  void timeoutLeavesTheTopUpPendingAndIsNeverRetried() throws Exception {
    // The PSP takes longer than our read timeout: it may well have taken the money.
    psp.stubFor(
        post(urlEqualTo("/v1/payments"))
            .willReturn(okPayment(201, "SUCCEEDED").withFixedDelay(3_000)));
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    topUp(tenant, "k-1", account, 50_000)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"));
    topUp(tenant, "k-1", account, 50_000)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replayed", "true"));

    assertThat(balance(tenant, account)).isZero(); // not credited on a guess
    assertThat(topUpStatuses(tenant)).containsExactly("PENDING"); // not failed on a guess either
    psp.verify(1, postRequestedFor(urlEqualTo("/v1/payments"))); // a timeout is never retried
  }

  @Test
  void connectionDroppedAfterSendingIsUnknownNotFailed() throws Exception {
    psp.stubFor(
        post(urlEqualTo("/v1/payments"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    topUp(tenant, "k-1", account, 50_000).andExpect(status().isAccepted());

    assertThat(topUpStatuses(tenant)).containsExactly("PENDING");
    psp.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
  }

  @Test
  void transient503IsRetriedAndThenSucceeds() throws Exception {
    psp.stubFor(
        post(urlEqualTo("/v1/payments"))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("recovered"));
    psp.stubFor(
        post(urlEqualTo("/v1/payments"))
            .inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(okPayment(201, "SUCCEEDED")));
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    topUp(tenant, "k-1", account, 700).andExpect(status().isCreated());

    psp.verify(2, postRequestedFor(urlEqualTo("/v1/payments")));
    assertThat(balance(tenant, account)).isEqualTo(700);
  }

  @Test
  void pspThatNeverAcceptsGives503AndTheSameKeyCanBeRetriedLater() throws Exception {
    psp.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(503)));
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    topUp(tenant, "k-1", account, 700)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value(ERRORS + "psp-unavailable"));
    psp.verify(3, postRequestedFor(urlEqualTo("/v1/payments"))); // 3 attempts, then give up
    assertThat(topUpStatuses(tenant)).containsExactly("FAILED"); // provably nothing moved

    pspCircuitBreaker.reset();
    psp.resetAll();
    pspAnswers(201, "SUCCEEDED");
    topUp(tenant, "k-1", account, 700).andExpect(status().isCreated()); // key was released
    assertThat(balance(tenant, account)).isEqualTo(700);
  }

  @Test
  void breakerOpensUnderPspFailureFailingFastWhileTransfersKeepWorking() throws Exception {
    psp.stubFor(post(urlEqualTo("/v1/payments")).willReturn(aResponse().withStatus(503)));
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 10_000);
    UUID other = api.createAccount(tenant);

    // Each failing top-up makes 3 attempts; after two, 6 of the last 6 calls failed: OPEN.
    topUp(tenant, "k-1", account, 100).andExpect(status().isServiceUnavailable());
    topUp(tenant, "k-2", account, 100).andExpect(status().isServiceUnavailable());
    assertThat(pspCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    int callsBefore = psp.getAllServeEvents().size();
    int transactionsBefore = topUpStatuses(tenant).size();

    topUp(tenant, "k-3", account, 100)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value(ERRORS + "psp-unavailable"));

    assertThat(psp.getAllServeEvents()).hasSize(callsBefore); // failed fast: PSP not called
    assertThat(topUpStatuses(tenant)).hasSize(transactionsBefore); // nothing written
    api.transfer(tenant, "t-1", account, other, 2_500).andExpect(status().isCreated()); // FR-7.3
  }

  @Test
  void onlyTheTenantsOwnUserAccountsCanBeToppedUp() throws Exception {
    pspAnswers(201, "SUCCEEDED");
    Tenant tenant = api.createTenant();
    Tenant other = api.createTenant();
    UUID foreign = api.createAccount(other);
    UUID treasury = data.systemAccountOf(tenant.id(), AccountType.TREASURY);

    topUp(tenant, "k-1", foreign, 100).andExpect(status().isNotFound());
    topUp(tenant, "k-2", treasury, 100).andExpect(status().isNotFound());
    psp.verify(0, postRequestedFor(urlEqualTo("/v1/payments")));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private ResultActions topUp(Tenant tenant, String key, UUID account, long amount)
      throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.post("/v1/topups")
            .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"%s\",\"amountMinor\":%d}".formatted(account, amount)));
  }

  private void pspAnswers(int status, String paymentStatus) {
    psp.stubFor(post(urlEqualTo("/v1/payments")).willReturn(okPayment(status, paymentStatus)));
  }

  private static ResponseDefinitionBuilder okPayment(int status, String paymentStatus) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"reference\":\"r\",\"status\":\"%s\"}".formatted(paymentStatus));
  }

  private long balance(Tenant tenant, UUID account) throws Exception {
    return api.read(
            mvc.perform(
                get("/v1/accounts/{id}", account)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))))
        .get("balanceMinor")
        .asLong();
  }

  private List<String> topUpStatuses(Tenant tenant) {
    return jdbc.sql("SELECT status FROM transactions WHERE tenant_id = ? AND type = 'TOPUP'")
        .param(tenant.id())
        .query(String.class)
        .list();
  }
}
