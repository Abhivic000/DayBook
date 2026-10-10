package dev.daybook.api.funding.application;

import java.util.UUID;

/**
 * Told when the sweeper resolves a PENDING transaction. The web layer implements it to give a
 * stranded idempotency key — left IN_PROGRESS when the API crashed between a request's two phases —
 * its final answer, without the application layer knowing how HTTP answers are rendered.
 */
public interface ResolvedTransactionListener {

  void resolved(UUID tenantId, UUID transactionId, PspOperationResult.Outcome outcome);
}
