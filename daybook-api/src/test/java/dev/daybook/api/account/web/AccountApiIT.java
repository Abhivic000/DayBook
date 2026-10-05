package dev.daybook.api.account.web;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ApiIntegrationTest
class AccountApiIT {

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  @Test
  void createIsIdempotentAndTheAccountCanBeRead() throws Exception {
    Tenant tenant = api.createTenant();

    MvcResult first =
        mvc.perform(
                post("/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                    .header("Idempotency-Key", "acct-1"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.type").value("USER"))
            .andExpect(jsonPath("$.balanceMinor").value(0))
            .andReturn();
    MvcResult replay =
        mvc.perform(
                post("/v1/accounts")
                    .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                    .header("Idempotency-Key", "acct-1"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn();

    String body = first.getResponse().getContentAsString();
    assertThat(replay.getResponse().getContentAsString()).isEqualTo(body);
    UUID accountId = UUID.fromString(json.readTree(body).get("id").asString());

    mvc.perform(
            get("/v1/accounts/{id}", accountId)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(accountId.toString()));
  }

  @Test
  void createWithoutIdempotencyKeyIs400() throws Exception {
    Tenant tenant = api.createTenant();

    mvc.perform(post("/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/missing-header"));
  }

  @Test
  void anotherTenantsAccountIs404NotForbidden() throws Exception {
    Tenant owner = api.createTenant();
    Tenant stranger = api.createTenant();
    UUID account = api.createAccount(owner);

    mvc.perform(
            get("/v1/accounts/{id}", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(stranger.apiKey())))
        .andExpect(status().isNotFound());
  }

  @Test
  void systemAccountsAreInvisibleToTenants() throws Exception {
    Tenant tenant = api.createTenant();
    UUID treasury =
        jdbc.sql("SELECT id FROM accounts WHERE tenant_id = ? AND type = 'TREASURY'")
            .param(tenant.id())
            .query(UUID.class)
            .single();

    mvc.perform(
            get("/v1/accounts/{id}", treasury)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isNotFound());
  }

  @Test
  void statementPagesNewestFirstWithoutGapsOrRepeats() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    for (long amount = 1; amount <= 5; amount++) {
      api.fund(tenant, account, amount);
    }

    List<Long> amounts = new ArrayList<>();
    List<Instant> times = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      var request =
          get("/v1/accounts/{id}/transactions", account)
              .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
              .param("limit", "2");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      JsonNode page = api.read(mvc.perform(request).andExpect(status().isOk()));
      for (JsonNode line : page.get("lines")) {
        amounts.add(line.get("amountMinor").asLong());
        times.add(Instant.parse(line.get("createdAt").asString()));
        assertThat(line.get("direction").asString()).isEqualTo("CREDIT");
      }
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
      pages++;
    } while (cursor != null);

    assertThat(pages).isEqualTo(3); // 2 + 2 + 1
    assertThat(amounts).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    assertThat(times).isSortedAccordingTo((a, b) -> b.compareTo(a)); // newest first
  }

  @Test
  void invalidPaginationParametersAre400() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    mvc.perform(
            get("/v1/accounts/{id}/transactions", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .param("cursor", "not-a-cursor"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/invalid-cursor"));
    mvc.perform(
            get("/v1/accounts/{id}/transactions", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .param("limit", "0"))
        .andExpect(status().isBadRequest());
  }
}
