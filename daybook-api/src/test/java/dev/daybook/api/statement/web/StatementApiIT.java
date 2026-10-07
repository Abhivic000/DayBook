package dev.daybook.api.statement.web;

import static dev.daybook.api.support.ApiClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.daybook.api.support.ApiClient;
import dev.daybook.api.support.ApiClient.Tenant;
import dev.daybook.api.support.ApiIntegrationTest;
import dev.daybook.api.support.ProjectionSchema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The statement endpoint reads the consumer's projection (ADR 0011). No consumer runs in these
 * tests, so they write projection rows themselves — which also lets them stage a lagging projection
 * on purpose.
 */
@ApiIntegrationTest
class StatementApiIT {

  @Autowired MockMvc mvc;
  @Autowired ApiClient api;
  @Autowired JdbcClient jdbc;
  @Autowired DataSource dataSource;

  @BeforeEach
  void projectionTablesExist() {
    ProjectionSchema.ensureMigrated(dataSource);
  }

  @Test
  void showsRunningBalanceNewestFirstAndSaysWhenItLagsTheLedger() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createFundedAccount(tenant, 1_000); // ledger v1
    UUID other = api.createAccount(tenant);
    api.transfer(tenant, "t-1", account, other, 300); // ledger v2
    project(tenant.id(), account, 1, "FUNDING", "CREDIT", 1_000, 1_000); // consumer saw v1 only

    mvc.perform(statement(tenant, account))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.consistency.projectedThroughVersion").value(1))
        .andExpect(jsonPath("$.consistency.ledgerVersion").value(2))
        .andExpect(jsonPath("$.consistency.upToDate").value(false));

    project(tenant.id(), account, 2, "TRANSFER", "DEBIT", 300, 700); // consumer catches up

    mvc.perform(statement(tenant, account))
        .andExpect(jsonPath("$.lines[0].accountVersion").value(2))
        .andExpect(jsonPath("$.lines[0].balanceAfterMinor").value(700))
        .andExpect(jsonPath("$.lines[1].balanceAfterMinor").value(1_000))
        .andExpect(jsonPath("$.consistency.upToDate").value(true))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
  }

  @Test
  void pagesThroughOlderLinesWithACursor() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);
    for (int v = 1; v <= 5; v++) {
      project(tenant.id(), account, v, "TRANSFER", "CREDIT", 10, v * 10L);
    }

    List<Long> versions = new ArrayList<>();
    String cursor = null;
    do {
      var request = statement(tenant, account).param("limit", "2");
      if (cursor != null) {
        request = request.param("cursor", cursor);
      }
      JsonNode page = api.read(mvc.perform(request).andExpect(status().isOk()));
      page.get("lines").forEach(line -> versions.add(line.get("accountVersion").asLong()));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
    } while (cursor != null);

    assertThat(versions).containsExactly(5L, 4L, 3L, 2L, 1L);
  }

  @Test
  void onlyTheOwningTenantsUserAccountsHaveStatements() throws Exception {
    Tenant owner = api.createTenant();
    Tenant stranger = api.createTenant();
    UUID account = api.createAccount(owner);
    UUID treasury =
        jdbc.sql("SELECT id FROM accounts WHERE tenant_id = ? AND type = 'TREASURY'")
            .param(owner.id())
            .query(UUID.class)
            .single();

    mvc.perform(statement(stranger, account)).andExpect(status().isNotFound());
    mvc.perform(statement(owner, treasury)).andExpect(status().isNotFound());
  }

  @Test
  void malformedCursorIs400() throws Exception {
    Tenant tenant = api.createTenant();
    UUID account = api.createAccount(tenant);

    mvc.perform(statement(tenant, account).param("cursor", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://daybook.dev/errors/invalid-cursor"));
  }

  // --- Helpers ----------------------------------------------------------------------------------

  private static MockHttpServletRequestBuilder statement(Tenant tenant, UUID account) {
    return get("/v1/accounts/{id}/statement", account)
        .header(HttpHeaders.AUTHORIZATION, bearer(tenant.apiKey()));
  }

  /** Writes what the consumer would write for one applied event. */
  private void project(
      UUID tenant,
      UUID account,
      long version,
      String type,
      String direction,
      long amount,
      long balanceAfter) {
    jdbc.sql(
            """
            INSERT INTO projection.statement_lines
                (event_id, tenant_id, account_id, account_version, transaction_id,
                 transaction_type, direction, amount_minor, balance_after_minor, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            UUID.randomUUID(),
            tenant,
            account,
            version,
            UUID.randomUUID(),
            type,
            direction,
            amount,
            balanceAfter,
            OffsetDateTime.now(ZoneOffset.UTC))
        .update();
    jdbc.sql(
            """
            INSERT INTO projection.account_balances
                (account_id, tenant_id, account_type, last_version, balance_minor)
            VALUES (?, ?, 'USER', ?, ?)
            ON CONFLICT (account_id) DO UPDATE
               SET last_version = EXCLUDED.last_version, balance_minor = EXCLUDED.balance_minor
            """)
        .params(account, tenant, version, balanceAfter)
        .update();
  }
}
