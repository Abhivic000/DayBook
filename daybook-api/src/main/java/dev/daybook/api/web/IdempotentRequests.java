package dev.daybook.api.web;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.funding.application.PspOperationResult;
import dev.daybook.api.idempotency.application.IdempotencyService;
import dev.daybook.api.idempotency.application.IdempotencyService.Begun;
import dev.daybook.api.idempotency.application.IdempotentResult;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import dev.daybook.api.idempotency.domain.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP side of idempotency: reads the key, fingerprints the request, and turns the stored answer
 * back into a response — byte-for-byte on replay, flagged with {@code Idempotent-Replayed}.
 */
@Component
public class IdempotentRequests {

  public static final String KEY_HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  /** What a successful action returns: the response body and the transaction it produced. */
  public record Success(Object body, @Nullable UUID transactionId) {}

  private final IdempotencyService idempotency;
  private final JsonMapper json;

  IdempotentRequests(IdempotencyService idempotency, JsonMapper json) {
    this.idempotency = idempotency;
    this.json = json;
  }

  /** A single-transaction request (transfer, funding, account creation): ADR 0007. */
  public ResponseEntity<String> execute(
      UUID tenantId,
      String key,
      HttpServletRequest request,
      Object requestBody,
      HttpStatus successStatus,
      Supplier<Success> action) {
    String fingerprint = fingerprint(key, request, requestBody);
    IdempotentResult result =
        idempotency.execute(
            tenantId,
            key,
            fingerprint,
            () -> {
              Success success = action.get();
              return new IdempotentResponse(
                  successStatus.value(),
                  json.writeValueAsString(success.body()),
                  success.transactionId());
            },
            this::rejection);
    return respond(result.response(), result.replayed());
  }

  /**
   * A two-phase request involving the PSP (top-up, withdrawal): ADR 0017.
   *
   * <ol>
   *   <li>{@code begin} records the PENDING transaction; it commits together with the key's claim.
   *   <li>{@code complete} calls the PSP (no database transaction open) and records the outcome.
   *   <li>The answer is stored under the key: 201 settled, 422 declined, 202 pending. If the PSP
   *       never accepted the request, the key is released instead and 503 returned, so the client
   *       may retry with the same key.
   * </ol>
   *
   * @param view renders the transaction for 201/202 bodies
   */
  public ResponseEntity<String> executeTwoPhase(
      UUID tenantId,
      String key,
      HttpServletRequest request,
      Object requestBody,
      Supplier<UUID> begin,
      Function<UUID, PspOperationResult> complete,
      Function<UUID, Object> view) {
    String fingerprint = fingerprint(key, request, requestBody);
    Begun begun = idempotency.begin(tenantId, key, fingerprint, begin, this::rejection);
    return switch (begun) {
      case Begun.Replay replay -> respond(replay.result().response(), true);
      case Begun.Rejected rejected -> respond(rejected.response(), false);
      case Begun.Started started -> {
        PspOperationResult result = complete.apply(started.transactionId());
        if (result.outcome() == PspOperationResult.Outcome.NOT_ACCEPTED) {
          idempotency.release(tenantId, key);
          yield respond(
              new IdempotentResponse(
                  HttpStatus.SERVICE_UNAVAILABLE.value(),
                  json.writeValueAsString(
                      Problems.of(
                          HttpStatus.SERVICE_UNAVAILABLE,
                          "psp-unavailable",
                          "The payment provider did not accept the request; nothing was charged."
                              + " Retry later with the same Idempotency-Key.")),
                  null),
              false);
        }
        IdempotentResponse answer = answerFor(result, view);
        idempotency.complete(tenantId, key, answer);
        yield respond(answer, false);
      }
    };
  }

  private IdempotentResponse answerFor(PspOperationResult result, Function<UUID, Object> view) {
    UUID id = result.transactionId();
    return switch (result.outcome()) {
      case SETTLED ->
          new IdempotentResponse(
              HttpStatus.CREATED.value(), json.writeValueAsString(view.apply(id)), id);
      case PENDING ->
          new IdempotentResponse(
              HttpStatus.ACCEPTED.value(), json.writeValueAsString(view.apply(id)), id);
      case DECLINED ->
          new IdempotentResponse(
              HttpStatus.UNPROCESSABLE_CONTENT.value(),
              json.writeValueAsString(
                  Problems.of(
                      HttpStatus.UNPROCESSABLE_CONTENT,
                      "payment-declined",
                      "The payment provider declined transaction " + id)),
              id);
      case NOT_ACCEPTED -> throw new IllegalStateException("Handled by the caller");
    };
  }

  private String fingerprint(String key, HttpServletRequest request, Object requestBody) {
    if (key.isBlank() || key.length() > 255) {
      throw new BadRequestException(
          "invalid-idempotency-key", "Idempotency-Key must be 1 to 255 characters");
    }
    // Canonical body: re-serialised from the parsed request object, so whitespace and key order
    // in what the client sent do not make an identical request look different.
    return RequestFingerprint.of(
        request.getMethod(), request.getRequestURI(), json.writeValueAsString(requestBody));
  }

  private IdempotentResponse rejection(DomainException rejection) {
    return new IdempotentResponse(
        HttpStatus.UNPROCESSABLE_CONTENT.value(),
        json.writeValueAsString(Problems.of(rejection)),
        null);
  }

  private static ResponseEntity<String> respond(IdempotentResponse response, boolean replayed) {
    return ResponseEntity.status(response.status())
        .contentType(
            response.status() < 400
                ? MediaType.APPLICATION_JSON
                : MediaType.APPLICATION_PROBLEM_JSON)
        .header(REPLAYED_HEADER, String.valueOf(replayed))
        .body(response.body());
  }
}
