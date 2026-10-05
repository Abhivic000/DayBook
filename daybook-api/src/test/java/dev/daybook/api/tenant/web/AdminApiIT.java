package dev.daybook.api.tenant.web;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@ApiIntegrationTest
class AdminApiIT {

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired JdbcClient jdbc;

  @Test
  void createdTenantGetsAWorkingKeyThatIsStoredOnlyAsAHash() throws Exception {
    Tenant tenant = api.createTenant();

    assertThat(tenant.apiKey()).startsWith("dbk_");
    String storedHash =
        jdbc.sql("SELECT secret_hash FROM api_keys WHERE tenant_id = ?")
            .param(tenant.id())
            .query(String.class)
            .single();
    assertThat(storedHash).hasSize(64).doesNotContain(tenant.apiKey());

    UUID account = api.createAccount(tenant); // the returned key authenticates
    mvc.perform(
            get("/v1/accounts/{id}", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isOk());
  }

  @Test
  void blankTenantNameIs400() throws Exception {
    mvc.perform(
            post("/v1/admin/tenants")
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void fundingAnUnknownTenantIs404() throws Exception {
    mvc.perform(
            post("/v1/admin/tenants/{id}/fundings", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                .header("Idempotency-Key", "f-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"amountMinor\":100}".formatted(UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  @Test
  void fundingTheTreasuryItselfIsRejected() throws Exception {
    Tenant tenant = api.createTenant();
    UUID treasury =
        jdbc.sql("SELECT id FROM accounts WHERE tenant_id = ? AND type = 'TREASURY'")
            .param(tenant.id())
            .query(UUID.class)
            .single();

    mvc.perform(
            post("/v1/admin/tenants/{id}/fundings", tenant.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                .header("Idempotency-Key", "f-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"amountMinor\":100}".formatted(treasury)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/account-type-not-allowed"));
  }

  @Test
  void fundingIsIdempotent() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    String body = "{\"accountId\":\"%s\",\"amountMinor\":700}".formatted(account);

    for (int i = 0; i < 3; i++) {
      mvc.perform(
              post("/v1/admin/tenants/{id}/fundings", tenant.id())
                  .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                  .header("Idempotency-Key", "same-funding")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isCreated());
    }

    mvc.perform(
            get("/v1/accounts/{id}", account)
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(jsonPath("$.balanceMinor").value(700));
  }
}
