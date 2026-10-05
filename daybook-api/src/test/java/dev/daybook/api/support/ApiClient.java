package dev.daybook.api.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the API over HTTP, the way a client would, to set up test scenarios. */
@TestComponent
public class ApiClient {

  /** A tenant as a client sees it: its id and its API key. */
  public record Tenant(UUID id, String apiKey) {}

  private final MockMvc mvc;
  private final JsonMapper json;

  ApiClient(MockMvc mvc, JsonMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  public Tenant createTenant() throws Exception {
    JsonNode body =
        read(
            mvc.perform(
                    post("/v1/admin/tenants")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"test tenant\"}"))
                .andExpect(status().isCreated()));
    return new Tenant(
        UUID.fromString(body.get("tenantId").asString()), body.get("apiKey").asString());
  }

  public UUID createAccount(Tenant tenant) throws Exception {
    JsonNode body =
        read(
            mvc.perform(
                    post("/v1/accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
    return UUID.fromString(body.get("id").asString());
  }

  public void fund(Tenant tenant, UUID accountId, long amountMinor) throws Exception {
    mvc.perform(
            post("/v1/admin/tenants/{tenantId}/fundings", tenant.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accountId\":\"%s\",\"amountMinor\":%d}".formatted(accountId, amountMinor)))
        .andExpect(status().isCreated());
  }

  public UUID createFundedAccount(Tenant tenant, long amountMinor) throws Exception {
    UUID id = createAccount(tenant);
    fund(tenant, id, amountMinor);
    return id;
  }

  public ResultActions transfer(
      Tenant tenant, String idempotencyKey, UUID from, UUID to, long amountMinor) throws Exception {
    return mvc.perform(
        post("/v1/transfers")
            .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amountMinor\":%d}"
                    .formatted(from, to, amountMinor)));
  }

  public JsonNode read(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  public static String bearer(String key) {
    return "Bearer " + key;
  }
}
