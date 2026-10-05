package dev.daybook.api.transfer.web;

import dev.daybook.api.common.domain.Money;
import dev.daybook.api.security.TenantPrincipal;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transfer.application.TransferCommand;
import dev.daybook.api.transfer.application.TransferService;
import dev.daybook.api.web.IdempotentRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransferController {

  /** Amounts are integers in minor units (paise): no decimals, no rounding. */
  record TransferRequest(
      @NotNull UUID fromAccountId,
      @NotNull UUID toAccountId,
      @NotNull @Positive Long amountMinor) {}

  private final TransferService transfers;
  private final TransactionQueries queries;
  private final IdempotentRequests idempotent;

  TransferController(
      TransferService transfers, TransactionQueries queries, IdempotentRequests idempotent) {
    this.transfers = transfers;
    this.queries = queries;
    this.idempotent = idempotent;
  }

  /** FR-4: settles synchronously; the response is the settled transaction with its entries. */
  @PostMapping("/v1/transfers")
  ResponseEntity<String> transfer(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @RequestHeader(IdempotentRequests.KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TransferRequest body,
      HttpServletRequest request) {
    UUID tenantId = tenant.tenantId();
    return idempotent.execute(
        tenantId,
        idempotencyKey,
        request,
        body,
        HttpStatus.CREATED,
        () -> {
          Transaction txn =
              transfers.transfer(
                  new TransferCommand(
                      tenantId,
                      body.fromAccountId(),
                      body.toAccountId(),
                      Money.ofMinor(body.amountMinor())));
          return new IdempotentRequests.Success(
              queries.find(tenantId, txn.id()).orElseThrow(), txn.id());
        });
  }
}
