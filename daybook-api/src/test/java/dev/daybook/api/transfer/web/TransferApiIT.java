package dev.daybook.api.transfer.web;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@ApiIntegrationTest
class TransferApiIT {

  private static final String ERRORS = "https://daybook.dev/errors/";

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;

  @Test
  void transferSettlesAndReturnsTheTransactionWithItsEntries() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10_000);
    UUID to = api.createAccount(tenant);

    api.transfer(tenant, "t-1", from, to, 2_500)
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string("Idempotent-Replayed", "false"))
        .andExpect(jsonPath("$.type").value("TRANSFER"))
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.amountMinor").value(2_500))
        .andExpect(jsonPath("$.entries", hasSize(2)));

    assertBalance(tenant, from, 7_500);
    assertBalance(tenant, to, 2_500);
  }

  @Test
  void replayWithSameKeyReturnsTheIdenticalResponseAndMovesMoneyOnce() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10_000);
    UUID to = api.createAccount(tenant);

    String first =
        api.transfer(tenant, "t-1", from, to, 1_000).andReturn().getResponse().getContentAsString();
    String replay =
        api.transfer(tenant, "t-1", from, to, 1_000)
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(replay).isEqualTo(first); // byte-for-byte
    assertBalance(tenant, from, 9_000);
  }

  @Test
  void sameKeyWithADifferentRequestIs422() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10_000);
    UUID to = api.createAccount(tenant);
    api.transfer(tenant, "t-1", from, to, 1_000);

    api.transfer(tenant, "t-1", from, to, 2_000)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(ERRORS + "idempotency-key-reused"));
    assertBalance(tenant, from, 9_000);
  }

  @Test
  void insufficientFundsIsA422ProblemThatIsStoredAndReplayed() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 100);
    UUID to = api.createAccount(tenant);

    String rejected =
        api.transfer(tenant, "t-1", from, to, 500)
            .andExpect(status().isUnprocessableContent())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value(ERRORS + "insufficient-funds"))
            .andExpect(jsonPath("$.title").value("Insufficient funds"))
            .andExpect(jsonPath("$.status").value(422))
            .andReturn()
            .getResponse()
            .getContentAsString();

    api.fund(tenant, from, 1_000); // could succeed now — but the key's answer is final (ADR 0007)
    String replay =
        api.transfer(tenant, "t-1", from, to, 500)
            .andExpect(status().isUnprocessableContent())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(replay).isEqualTo(rejected);
    assertBalance(tenant, from, 1_100);
  }

  @Test
  void validationFailuresAre400WithPerFieldDetailAndAreNotStored() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 1_000);

    mvc.perform(
            post("/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .header("Idempotency-Key", "t-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"%s\",\"amountMinor\":-5}".formatted(from)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(ERRORS + "validation-failed"))
        .andExpect(jsonPath("$.errors[?(@.field == 'toAccountId')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field == 'amountMinor')]").exists());

    // The key was never claimed, so a corrected request with it goes through.
    UUID to = api.createAccount(tenant);
    api.transfer(tenant, "t-1", from, to, 100).andExpect(status().isCreated());
  }

  @Test
  void malformedJsonIs400() throws Exception {
    Tenant tenant = api.createTenant();

    mvc.perform(
            post("/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .header("Idempotency-Key", "t-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(ERRORS + "malformed-request"));
  }

  @Test
  void transferToAnotherTenantsAccountIs404() throws Exception {
    Tenant tenant = api.createTenant();
    Tenant other = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 1_000);
    UUID foreign = api.createAccount(other);

    api.transfer(tenant, "t-1", from, foreign, 100).andExpect(status().isNotFound());
    assertBalance(tenant, from, 1_000);
  }

  @Test
  void clientCorrelationIdIsEchoedInHeaderAndProblemBody() throws Exception {
    Tenant tenant = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 10);
    UUID to = api.createAccount(tenant);

    mvc.perform(
            post("/v1/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .header("Idempotency-Key", "t-1")
                .header("X-Correlation-Id", "trace-abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amountMinor\":50}"
                        .formatted(from, to)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(header().string("X-Correlation-Id", "trace-abc-123"))
        .andExpect(jsonPath("$.correlationId").value("trace-abc-123"));
  }

  @Test
  void transactionCanBeFetchedByItsOwnerOnly() throws Exception {
    Tenant tenant = api.createTenant();
    Tenant other = api.createTenant();
    UUID from = api.createFundedAccount(tenant, 1_000);
    UUID to = api.createAccount(tenant);
    JsonNode created = api.read(api.transfer(tenant, "t-1", from, to, 100));
    String transactionId = created.get("id").asString();

    mvc.perform(
            get("/v1/transactions/{id}", transactionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(transactionId))
        .andExpect(jsonPath("$.entries", hasSize(2)));
    mvc.perform(
            get("/v1/transactions/{id}", transactionId)
                .header(HttpHeaders.AUTHORIZATION, bearer(other.apiKey())))
        .andExpect(status().isNotFound());
  }

  private void assertBalance(Tenant tenant, UUID account, long expected) throws Exception {
    mvc.perform(
            get("/v1/accounts/{id}", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(jsonPath("$.balanceMinor").value(expected));
  }
}
