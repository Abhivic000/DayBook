package dev.daybook.api.statement.application;

import dev.daybook.api.account.application.AccountService;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.statement.application.StatementQueries.ProjectedPage;
import dev.daybook.api.statement.application.StatementQueries.StatementLine;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The running-balance statement (ADR 0011), served from the consumer's projection and therefore
 * eventually consistent. Every response states how far behind the ledger it may be, so clients
 * never have to guess.
 */
@Service
public class StatementService {

  /**
   * How fresh the statement is.
   *
   * @param projectedThroughVersion the newest account version reflected in the statement
   * @param ledgerVersion the account's current version in the ledger (source of truth)
   * @param upToDate whether the statement already includes every ledger entry
   */
  public record Consistency(long projectedThroughVersion, long ledgerVersion, boolean upToDate) {}

  /**
   * A page of the statement.
   *
   * @param nextCursor pass as {@code cursor} for the next (older) page; null on the last page
   */
  public record Statement(
      UUID accountId,
      List<StatementLine> lines,
      @Nullable String nextCursor,
      Consistency consistency) {}

  private final AccountService accounts;
  private final StatementQueries projection;

  StatementService(AccountService accounts, StatementQueries projection) {
    this.accounts = accounts;
    this.projection = projection;
  }

  /**
   * One page of the account's statement, with its consistency against the ledger.
   *
   * @throws dev.daybook.api.account.domain.AccountNotFoundException not the tenant's USER account
   * @throws StatementUnavailableException the projection does not exist yet
   */
  public Statement statement(
      UUID tenantId, UUID accountId, @Nullable Long beforeVersion, int limit) {
    accounts.getUserAccount(tenantId, accountId); // 404 before touching the projection
    ProjectedPage page = projection.page(tenantId, accountId, beforeVersion, limit);
    // Read the ledger *after* the projection: the ledger only moves forward, so this guarantees
    // ledgerVersion >= projectedThroughVersion and "upToDate" is never wrongly reported.
    Account ledger = accounts.getUserAccount(tenantId, accountId);
    String nextCursor =
        page.hasMore() ? Long.toString(page.lines().getLast().accountVersion()) : null;
    return new Statement(
        accountId,
        page.lines(),
        nextCursor,
        new Consistency(
            page.projectedThroughVersion(),
            ledger.version(),
            page.projectedThroughVersion() >= ledger.version()));
  }
}
