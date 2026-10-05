package dev.daybook.api.security;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@ApiIntegrationTest
class SecurityApiIT {

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired JdbcClient jdbc;

  @Test
  void healthProbesArePublic() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void missingKeyIs401ProblemWithCorrelationId() throws Exception {
    mvc.perform(get("/v1/accounts/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/unauthorized"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void unknownOrMalformedKeysAre401() throws Exception {
    for (String key : new String[] {"dbk_0000000000000000_nope", "not-a-key", "dbk__"}) {
      mvc.perform(
              get("/v1/accounts/{id}", UUID.randomUUID())
                  .header(HttpHeaders.AUTHORIZATION, bearer(key)))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void correctKeyIdWithWrongSecretIs401() throws Exception {
    ApiClient.Tenant tenant = api.createTenant();
    String keyIdOnly = tenant.apiKey().substring(0, tenant.apiKey().lastIndexOf('_'));

    mvc.perform(
            get("/v1/accounts/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(keyIdOnly + "_wrongsecret")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revokedKeyIs401() throws Exception {
    ApiClient.Tenant tenant = api.createTenant();
    jdbc.sql("UPDATE api_keys SET status = 'REVOKED' WHERE tenant_id = ?")
        .param(tenant.id())
        .update();

    mvc.perform(
            get("/v1/accounts/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tenantKeyCannotUseAdminEndpoints() throws Exception {
    ApiClient.Tenant tenant = api.createTenant();

    mvc.perform(
            post("/v1/admin/tenants")
                .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sneaky\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/forbidden"));
  }

  @Test
  void adminKeyCannotUseTenantEndpoints() throws Exception {
    mvc.perform(
            get("/v1/accounts/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(ApiIntegrationTest.ADMIN_KEY)))
        .andExpect(status().isForbidden());
  }

  @Test
  void unknownPathsAreDeniedEvenWithAValidKey() throws Exception {
    ApiClient.Tenant tenant = api.createTenant();

    mvc.perform(
            get("/internal/anything").header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey())))
        .andExpect(status().isForbidden());
  }
}
