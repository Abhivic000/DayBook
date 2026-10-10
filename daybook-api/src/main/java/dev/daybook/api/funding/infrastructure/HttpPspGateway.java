package dev.daybook.api.funding.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.PspGateway;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to the PSP over HTTP. The essential job is classifying every failure by one question:
 * <em>could the PSP have acted on this request?</em>
 *
 * <table>
 *   <caption>Failure classification</caption>
 *   <tr><th>What happened</th><th>PSP may have acted?</th><th>Result</th></tr>
 *   <tr><td>connection refused / connect timeout</td><td>no</td><td>retry, then NotAccepted</td></tr>
 *   <tr><td>HTTP 503</td><td>no</td><td>retry, then NotAccepted</td></tr>
 *   <tr><td>other HTTP 4xx (bad request)</td><td>no</td><td>NotAccepted, no retry</td></tr>
 *   <tr><td>read timeout, connection reset, HTTP 5xx</td><td>maybe</td><td>Unknown, never
 *       retried</td></tr>
 *   <tr><td>200/201 with a status</td><td>yes, known</td><td>Succeeded / Declined</td></tr>
 * </table>
 */
@Component
class HttpPspGateway implements PspGateway {

  private final RestClient http;
  private final CircuitBreaker breaker;
  private final Retry retry;
  private final Bulkhead bulkhead;

  HttpPspGateway(RestClient pspRestClient, CircuitBreaker breaker, Retry retry, Bulkhead bulkhead) {
    this.http = pspRestClient;
    this.breaker = breaker;
    this.retry = retry;
    this.bulkhead = bulkhead;
  }

  @Override
  public boolean acceptingRequests() {
    CircuitBreaker.State state = breaker.getState();
    return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
  }

  @Override
  public Outcome createPayment(String reference, Direction direction, Money amount) {
    Supplier<Outcome> attempt = () -> send(reference, direction, amount);
    // retry( breaker( bulkhead( attempt ) ) ): the breaker sees every attempt, retry wraps them
    // all.
    Supplier<Outcome> resilient =
        Retry.decorateSupplier(
            retry,
            CircuitBreaker.decorateSupplier(breaker, Bulkhead.decorateSupplier(bulkhead, attempt)));
    try {
      return resilient.get();
    } catch (CallNotPermittedException e) {
      return new Outcome.NotAccepted("circuit breaker open");
    } catch (BulkheadFullException e) {
      return new Outcome.NotAccepted("too many concurrent PSP calls");
    } catch (PspNotAcceptedException e) {
      return new Outcome.NotAccepted(e.getMessage());
    } catch (PspUnknownOutcomeException e) {
      return new Outcome.Unknown(e.getMessage());
    }
  }

  /**
   * Status lookups go through the same circuit breaker and bulkhead — a down PSP is down for both —
   * but are not retried here: the sweeper simply asks again on its next run.
   */
  @Override
  public PaymentStatus paymentStatus(String reference) {
    Supplier<PaymentStatus> lookup =
        CircuitBreaker.decorateSupplier(
            breaker, Bulkhead.decorateSupplier(bulkhead, () -> fetchStatus(reference)));
    try {
      return lookup.get();
    } catch (CallNotPermittedException e) {
      return new PaymentStatus.Unavailable("circuit breaker open");
    } catch (BulkheadFullException e) {
      return new PaymentStatus.Unavailable("too many concurrent PSP calls");
    } catch (PspNotAcceptedException | PspUnknownOutcomeException e) {
      return new PaymentStatus.Unavailable(e.getMessage());
    }
  }

  private PaymentStatus fetchStatus(String reference) {
    try {
      return http.get()
          .uri("/v1/payments/{reference}", reference)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 200) {
                  PaymentResponse payment = response.bodyTo(PaymentResponse.class);
                  String paymentStatus = payment == null ? null : payment.status();
                  if ("SUCCEEDED".equals(paymentStatus)) {
                    return new PaymentStatus.Succeeded();
                  }
                  if ("DECLINED".equals(paymentStatus)) {
                    return new PaymentStatus.Declined();
                  }
                  throw new PspUnknownOutcomeException("Unrecognised PSP status " + paymentStatus);
                }
                if (status == 404) {
                  return new PaymentStatus.NotFound(); // a valid answer, not a PSP failure
                }
                throw new PspUnknownOutcomeException("PSP status lookup answered " + status);
              });
    } catch (ResourceAccessException e) {
      throw new PspUnknownOutcomeException("PSP status lookup failed: " + e.getMessage());
    }
  }

  private Outcome send(String reference, Direction direction, Money amount) {
    try {
      return http.post()
          .uri("/v1/payments")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              Map.of(
                  "reference", reference,
                  "direction", direction.name(),
                  "amountMinor", amount.minor()))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 200 || status == 201) {
                  return toOutcome(response.bodyTo(PaymentResponse.class));
                }
                if (status == 503) {
                  throw new PspNotAcceptedException("PSP answered 503", true);
                }
                if (status >= 400 && status < 500 && status != 409) {
                  throw new PspNotAcceptedException("PSP rejected the request: " + status, false);
                }
                // 409 (reference reused with different details) or any 5xx: we cannot tell
                // whether the PSP acted. Never guess.
                throw new PspUnknownOutcomeException("PSP answered " + status);
              });
    } catch (ResourceAccessException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
        throw new PspNotAcceptedException("PSP unreachable: " + cause.getMessage(), true);
      }
      // The request may have reached the PSP: read timeout, reset connection, ...
      throw new PspUnknownOutcomeException(
          "No answer from PSP: "
              + (cause == null ? e : cause).getClass().getSimpleName()
              + ": "
              + (cause == null ? e.getMessage() : cause.getMessage()));
    }
  }

  private static Outcome toOutcome(@Nullable PaymentResponse payment) {
    if (payment == null || payment.status() == null) {
      return new Outcome.Unknown("PSP answer had no status");
    }
    return switch (payment.status()) {
      case "SUCCEEDED" -> new Outcome.Succeeded();
      case "DECLINED" -> new Outcome.Declined();
      default -> new Outcome.Unknown("Unrecognised PSP status " + payment.status());
    };
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PaymentResponse(@Nullable String reference, @Nullable String status) {}

  /** The PSP provably did not act. {@code retryable}: whether trying again could help. */
  static final class PspNotAcceptedException extends RuntimeException {
    private final boolean retryable;

    PspNotAcceptedException(String message, boolean retryable) {
      super(message);
      this.retryable = retryable;
    }

    boolean retryable() {
      return retryable;
    }
  }

  /** The PSP may have acted; the outcome is unknown. Never retried. */
  static final class PspUnknownOutcomeException extends RuntimeException {
    PspUnknownOutcomeException(String message) {
      super(message);
    }
  }
}
