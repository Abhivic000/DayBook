package dev.daybook.api.funding.web;

import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.TopUpService;
import dev.daybook.api.funding.application.TopUpService.TopUpCommand;
import dev.daybook.api.security.TenantPrincipal;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.web.IdempotentRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TopUpController {

  /** Amounts are integers in minor units (paise). */
  record TopUpRequest(@NotNull UUID accountId, @NotNull @Positive Long amountMinor) {}

  private final TopUpService topUps;
  private final TransactionQueries queries;
  private final IdempotentRequests idempotent;

  TopUpController(TopUpService topUps, TransactionQueries queries, IdempotentRequests idempotent) {
    this.topUps = topUps;
    this.queries = queries;
    this.idempotent = idempotent;
  }

  /**
   * Money in from the payment provider (FR-6, ADR 0017): 201 settled, 202 pending (outcome not yet
   * known — follow {@code GET /v1/transactions/{id}}), 422 declined, 503 provider unavailable.
   */
  @PostMapping("/v1/topups")
  ResponseEntity<String> topUp(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @RequestHeader(IdempotentRequests.KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TopUpRequest body,
      HttpServletRequest request) {
    UUID tenantId = tenant.tenantId();
    TopUpCommand command =
        new TopUpCommand(tenantId, body.accountId(), Money.ofMinor(body.amountMinor()));
    return idempotent.executeTwoPhase(
        tenantId,
        idempotencyKey,
        request,
        body,
        () -> topUps.begin(command),
        transactionId -> topUps.complete(command, transactionId),
        transactionId -> queries.find(tenantId, transactionId).orElseThrow());
  }
}
