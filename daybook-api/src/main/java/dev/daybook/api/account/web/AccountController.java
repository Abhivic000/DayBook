package dev.daybook.api.account.web;

import dev.daybook.api.account.application.AccountService;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountStatus;
import dev.daybook.api.account.domain.AccountType;
import dev.daybook.api.security.TenantPrincipal;
import dev.daybook.api.transaction.application.TransactionQueries;
import dev.daybook.api.transaction.application.TransactionQueries.StatementPage;
import dev.daybook.api.web.BadRequestException;
import dev.daybook.api.web.IdempotentRequests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountController {

  static final int MAX_PAGE_SIZE = 100;

  record AccountResponse(
      UUID id, AccountType type, long balanceMinor, long version, AccountStatus status) {

    static AccountResponse from(Account account) {
      return new AccountResponse(
          account.id(),
          account.type(),
          account.balance().minor(),
          account.version(),
          account.status());
    }
  }

  private final AccountService accounts;
  private final TransactionQueries queries;
  private final IdempotentRequests idempotent;

  AccountController(
      AccountService accounts, TransactionQueries queries, IdempotentRequests idempotent) {
    this.accounts = accounts;
    this.queries = queries;
    this.idempotent = idempotent;
  }

  /** FR-2.1. The request has no body: every new account is an empty USER account. */
  @PostMapping
  ResponseEntity<String> create(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @RequestHeader(IdempotentRequests.KEY_HEADER) String idempotencyKey,
      HttpServletRequest request) {
    return idempotent.execute(
        tenant.tenantId(),
        idempotencyKey,
        request,
        Map.of(),
        HttpStatus.CREATED,
        () ->
            new IdempotentRequests.Success(
                AccountResponse.from(accounts.openUserAccount(tenant.tenantId())), null));
  }

  /** FR-2.2. */
  @GetMapping("/{accountId}")
  AccountResponse get(
      @AuthenticationPrincipal TenantPrincipal tenant, @PathVariable UUID accountId) {
    return AccountResponse.from(accounts.getUserAccount(tenant.tenantId(), accountId));
  }

  /** FR-2.3: newest first, cursor-paginated. */
  @GetMapping("/{accountId}/transactions")
  StatementPage statement(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) @Nullable String cursor) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new BadRequestException(
          "invalid-parameter", "limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    accounts.getUserAccount(tenant.tenantId(), accountId); // 404 unless it is the tenant's
    try {
      return queries.statement(tenant.tenantId(), accountId, cursor, limit);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("invalid-cursor", "cursor is not valid");
    }
  }
}
