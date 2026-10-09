package dev.daybook.api.idempotency.application;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.idempotency.domain.IdempotencyKeyReusedException;
import dev.daybook.api.idempotency.domain.IdempotencyRecord;
import dev.daybook.api.idempotency.domain.IdempotencyRequestInProgressException;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a mutating request at most once per {@code (tenant, key)} (FR-5, ADR 0007).
 *
 * <p>The key is claimed, the action runs, and the response is stored — all in one database
 * transaction, so the key and the request's effect commit or roll back together.
 */
@Service
public class IdempotencyService {

  private final IdempotencyRepository repository;
  private final IdempotencyProperties properties;
  private final TransactionTemplate savepoint;

  IdempotencyService(
      IdempotencyRepository repository,
      IdempotencyProperties properties,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.properties = properties;
    this.savepoint = new TransactionTemplate(transactionManager);
    this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  /**
   * Returns the key's final answer, running {@code action} only if the key is new.
   *
   * @param action performs the request and returns its success response
   * @param onRejection turns a business rejection into the response to store (typically 422)
   * @throws IdempotencyKeyReusedException the key was used with a different request
   * @throws IdempotencyRequestInProgressException the original request has no final answer yet
   */
  @Transactional
  public IdempotentResult execute(
      UUID tenantId,
      String key,
      String requestHash,
      Supplier<IdempotentResponse> action,
      Function<DomainException, IdempotentResponse> onRejection) {
    requireValidKey(key);
    if (repository.tryClaim(tenantId, key, requestHash, properties.retention())) {
      IdempotentResponse response = runOnce(action, onRejection);
      repository.complete(tenantId, key, response);
      return new IdempotentResult(response, false);
    }
    return replayExisting(tenantId, key, requestHash);
  }

  /** How a two-phase request's first phase ended. */
  public sealed interface Begun {

    /** The key already had a final answer: return it, do nothing else. */
    record Replay(IdempotentResult result) implements Begun {}

    /** A business rule rejected the request before anything started; the answer is stored. */
    record Rejected(IdempotentResponse response) implements Begun {}

    /** The operation started: the key stays IN_PROGRESS, linked to this transaction. */
    record Started(UUID transactionId) implements Begun {}
  }

  /**
   * Phase one of a two-phase request (ADR 0017): claims the key and runs {@code start} — which
   * records the PENDING transaction — in one database transaction that commits before the caller
   * contacts the PSP. The key then stays IN_PROGRESS until {@link #complete} or {@link #release}; a
   * concurrent retry meanwhile gets 409.
   *
   * @param start validates and records the operation, returning its transaction id
   * @throws IdempotencyKeyReusedException the key was used with a different request
   * @throws IdempotencyRequestInProgressException the original request has no final answer yet
   */
  @Transactional
  public Begun begin(
      UUID tenantId,
      String key,
      String requestHash,
      Supplier<UUID> start,
      Function<DomainException, IdempotentResponse> onRejection) {
    requireValidKey(key);
    if (!repository.tryClaim(tenantId, key, requestHash, properties.retention())) {
      return new Begun.Replay(replayExisting(tenantId, key, requestHash));
    }
    try {
      UUID transactionId = savepoint.execute(status -> start.get());
      repository.linkTransaction(tenantId, key, transactionId);
      return new Begun.Started(transactionId);
    } catch (DomainException rejection) {
      IdempotentResponse response = onRejection.apply(rejection);
      repository.complete(tenantId, key, response);
      return new Begun.Rejected(response);
    }
  }

  /** Phase two: stores the request's answer under its key. */
  @Transactional
  public void complete(UUID tenantId, String key, IdempotentResponse response) {
    repository.complete(tenantId, key, response);
  }

  /**
   * Phase two when the request provably had no effect (e.g. the PSP never received it): forgets the
   * key, so the client may retry with it as though the first attempt never happened.
   */
  @Transactional
  public void release(UUID tenantId, String key) {
    repository.release(tenantId, key);
  }

  private static void requireValidKey(String key) {
    if (key.isBlank() || key.length() > 255) {
      throw new IllegalArgumentException("Idempotency key must be 1-255 characters");
    }
  }

  private IdempotentResult replayExisting(UUID tenantId, String key, String requestHash) {
    IdempotencyRecord existing =
        repository
            .find(tenantId, key)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Key %s conflicted but was not found".formatted(key)));
    if (!existing.requestHash().equals(requestHash)) {
      throw new IdempotencyKeyReusedException(key);
    }
    return existing
        .completedResponse()
        .map(stored -> new IdempotentResult(stored, true))
        .orElseThrow(() -> new IdempotencyRequestInProgressException(key));
  }

  /**
   * Runs the action inside a savepoint. A business rejection rolls back to the savepoint — undoing
   * anything the action wrote — while the surrounding transaction stays usable, so the rejection
   * can be stored and committed as the key's final answer. Any other exception propagates and rolls
   * back everything, including the claim on the key, so a retry is safe.
   */
  private IdempotentResponse runOnce(
      Supplier<IdempotentResponse> action,
      Function<DomainException, IdempotentResponse> onRejection) {
    try {
      return savepoint.execute(status -> action.get());
    } catch (DomainException rejection) {
      return onRejection.apply(rejection);
    }
  }
}
