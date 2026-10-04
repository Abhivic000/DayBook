package dev.daybook.api.idempotency.application;

import dev.daybook.api.idempotency.domain.IdempotentResponse;

/**
 * The outcome of an idempotent execution.
 *
 * @param response the request's final answer
 * @param replayed {@code true} if this is a stored answer to an earlier request with the same key,
 *     {@code false} if the action ran now
 */
public record IdempotentResult(IdempotentResponse response, boolean replayed) {}
