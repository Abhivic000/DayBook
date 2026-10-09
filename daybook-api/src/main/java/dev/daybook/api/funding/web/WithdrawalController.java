package dev.daybook.api.funding.web;

import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.WithdrawalService;
import dev.daybook.api.funding.application.WithdrawalService.WithdrawalCommand;
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
class WithdrawalController {

  /** Amounts are integers in minor units (paise). */
  record WithdrawalRequest(@NotNull UUID accountId, @NotNull @Positive Long amountMinor) {}

  private final WithdrawalService withdrawals;
  private final TransactionQueries queries;
  private final IdempotentRequests idempotent;

  WithdrawalController(
      WithdrawalService withdrawals, TransactionQueries queries, IdempotentRequests idempotent) {
    this.withdrawals = withdrawals;
    this.queries = queries;
    this.idempotent = idempotent;
  }

  /**
   * Money out to the payment provider (FR-6, ADR 0006, ADR 0017): 201 paid out, 202 pending (funds
   * held until the outcome is known), 422 insufficient funds or declined (hold returned), 503
   * provider unavailable (nothing held).
   */
  @PostMapping("/v1/withdrawals")
  ResponseEntity<String> withdraw(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @RequestHeader(IdempotentRequests.KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody WithdrawalRequest body,
      HttpServletRequest request) {
    UUID tenantId = tenant.tenantId();
    WithdrawalCommand command =
        new WithdrawalCommand(tenantId, body.accountId(), Money.ofMinor(body.amountMinor()));
    return idempotent.executeTwoPhase(
        tenantId,
        idempotencyKey,
        request,
        body,
        () -> withdrawals.begin(command),
        transactionId -> withdrawals.complete(command, transactionId),
        transactionId -> queries.find(tenantId, transactionId).orElseThrow());
  }
}
