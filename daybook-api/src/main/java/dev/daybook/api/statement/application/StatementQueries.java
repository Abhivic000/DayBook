package dev.daybook.api.statement.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only access to the consumer's statement projection (ADR 0011). The API never writes these
 * tables; its database role is granted SELECT only.
 */
public interface StatementQueries {

  /** One line of the running-balance statement. */
  record StatementLine(
      long accountVersion,
      UUID transactionId,
      String transactionType,
      String direction,
      long amountMinor,
      long balanceAfterMinor,
      Instant occurredAt) {}

  /**
   * A page of the projection for one account.
   *
   * @param hasMore whether older lines exist beyond this page
   * @param projectedThroughVersion the latest account version the projection has applied
   */
  record ProjectedPage(List<StatementLine> lines, boolean hasMore, long projectedThroughVersion) {}

  /**
   * Lines newest first.
   *
   * @param beforeVersion return only lines older than this version; null for the newest page
   * @throws StatementUnavailableException the projection does not exist yet (consumer never ran)
   */
  ProjectedPage page(UUID tenantId, UUID accountId, @Nullable Long beforeVersion, int limit);
}
