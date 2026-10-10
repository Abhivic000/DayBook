package dev.daybook.api.web;

import dev.daybook.api.funding.application.PspOperationResult;
import dev.daybook.api.funding.application.ResolvedTransactionListener;
import dev.daybook.api.idempotency.application.IdempotencyService;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import dev.daybook.api.transaction.application.TransactionQueries;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * When the sweeper resolves a transaction whose request crashed between its two phases, the
 * request's idempotency key is still IN_PROGRESS and every retry gets 409. This gives it the final
 * answer the request would have stored — so a retry with that key now returns the outcome.
 */
@Component
class StrandedKeyCompleter implements ResolvedTransactionListener {

  private final IdempotencyService idempotency;
  private final TransactionQueries queries;
  private final JsonMapper json;

  StrandedKeyCompleter(
      IdempotencyService idempotency, TransactionQueries queries, JsonMapper json) {
    this.idempotency = idempotency;
    this.queries = queries;
    this.json = json;
  }

  @Override
  public void resolved(UUID tenantId, UUID transactionId, PspOperationResult.Outcome outcome) {
    IdempotentResponse answer =
        switch (outcome) {
          case SETTLED ->
              new IdempotentResponse(
                  HttpStatus.CREATED.value(),
                  json.writeValueAsString(queries.find(tenantId, transactionId).orElseThrow()),
                  transactionId);
          case DECLINED ->
              new IdempotentResponse(
                  HttpStatus.UNPROCESSABLE_CONTENT.value(),
                  json.writeValueAsString(
                      Problems.of(
                          HttpStatus.UNPROCESSABLE_CONTENT,
                          "payment-declined",
                          "The payment provider declined transaction " + transactionId)),
                  transactionId);
          case PENDING, NOT_ACCEPTED -> null; // not final: nothing to store
        };
    if (answer != null) {
      idempotency.completeStranded(tenantId, transactionId, answer);
    }
  }
}
