package dev.daybook.api.transaction.web;

import dev.daybook.api.security.TenantPrincipal;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.transaction.application.TransactionQueries.TransactionView;
import dev.daybook.api.transaction.domain.TransactionNotFoundException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransactionController {

  private final TransactionQueries queries;

  TransactionController(TransactionQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/v1/transactions/{transactionId}")
  TransactionView get(
      @AuthenticationPrincipal TenantPrincipal tenant, @PathVariable UUID transactionId) {
    return queries
        .find(tenant.tenantId(), transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
  }
}
