package dev.daybook.api.statement.web;

import dev.daybook.api.security.TenantPrincipal;
import dev.daybook.api.statement.application.StatementService;
import dev.daybook.api.statement.application.StatementService.Statement;
import dev.daybook.api.web.BadRequestException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatementController {

  static final int MAX_PAGE_SIZE = 100;

  private final StatementService statements;

  StatementController(StatementService statements) {
    this.statements = statements;
  }

  /**
   * Running-balance statement, newest first (ADR 0011). Eventually consistent: see the {@code
   * consistency} block in the response for how current it is.
   */
  @GetMapping("/v1/accounts/{accountId}/statement")
  Statement statement(
      @AuthenticationPrincipal TenantPrincipal tenant,
      @PathVariable UUID accountId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) @Nullable String cursor) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new BadRequestException(
          "invalid-parameter", "limit must be between 1 and " + MAX_PAGE_SIZE);
    }
    return statements.statement(tenant.tenantId(), accountId, parseCursor(cursor), limit);
  }

  private static @Nullable Long parseCursor(@Nullable String cursor) {
    if (cursor == null) {
      return null;
    }
    try {
      long version = Long.parseLong(cursor);
      if (version < 1) {
        throw new NumberFormatException();
      }
      return version;
    } catch (NumberFormatException e) {
      throw new BadRequestException("invalid-cursor", "cursor is not valid");
    }
  }
}
