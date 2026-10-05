package dev.daybook.api.web;

import dev.daybook.api.idempotency.application.IdempotencyService;
import dev.daybook.api.idempotency.application.IdempotentResult;
import dev.daybook.api.idempotency.domain.IdempotentResponse;
import dev.daybook.api.idempotency.domain.RequestFingerprint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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

  public ResponseEntity<String> execute(
      UUID tenantId,
      String key,
      HttpServletRequest request,
      Object requestBody,
      HttpStatus successStatus,
      Supplier<Success> action) {
    if (key.isBlank() || key.length() > 255) {
      throw new BadRequestException(
          "invalid-idempotency-key", "Idempotency-Key must be 1 to 255 characters");
    }
    // Canonical body: re-serialised from the parsed request object, so whitespace and key order
    // in what the client sent do not make an identical request look different.
    String fingerprint =
        RequestFingerprint.of(
            request.getMethod(), request.getRequestURI(), json.writeValueAsString(requestBody));

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
            rejection ->
                new IdempotentResponse(
                    HttpStatus.UNPROCESSABLE_CONTENT.value(),
                    json.writeValueAsString(Problems.of(rejection)),
                    null));

    IdempotentResponse response = result.response();
    return ResponseEntity.status(response.status())
        .contentType(
            response.status() < 400
                ? MediaType.APPLICATION_JSON
                : MediaType.APPLICATION_PROBLEM_JSON)
        .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
        .body(response.body());
  }
}
