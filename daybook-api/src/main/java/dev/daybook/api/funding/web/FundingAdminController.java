package dev.daybook.api.funding.web;

import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.FundingService;
import dev.daybook.api.tenant.application.TenantService;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.web.IdempotentRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only opening balances from the tenant's TREASURY (ADR 0002 amendment). */
@RestController
class FundingAdminController {

  record FundingRequest(@NotNull UUID accountId, @NotNull @Positive Long amountMinor) {}

  private final TenantService tenants;
  private final FundingService funding;
  private final TransactionQueries queries;
  private final IdempotentRequests idempotent;

  FundingAdminController(
      TenantService tenants,
      FundingService funding,
      TransactionQueries queries,
      IdempotentRequests idempotent) {
    this.tenants = tenants;
    this.funding = funding;
    this.queries = queries;
    this.idempotent = idempotent;
  }

  /** Idempotent, with the key scoped to the tenant being funded. */
  @PostMapping("/v1/admin/tenants/{tenantId}/fundings")
  ResponseEntity<String> fund(
      @PathVariable UUID tenantId,
      @RequestHeader(IdempotentRequests.KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody FundingRequest body,
      HttpServletRequest request) {
    tenants.requireExists(tenantId); // 404 before claiming a key under a tenant that is not there
    return idempotent.execute(
        tenantId,
        idempotencyKey,
        request,
        body,
        HttpStatus.CREATED,
        () -> {
          Transaction txn =
              funding.fund(tenantId, body.accountId(), Money.ofMinor(body.amountMinor()));
          return new IdempotentRequests.Success(
              queries.find(tenantId, txn.id()).orElseThrow(), txn.id());
        });
  }
}
