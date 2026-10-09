package dev.daybook.pspsim.web;

import dev.daybook.pspsim.behaviour.SimulatorBehaviour;
import dev.daybook.pspsim.behaviour.SimulatorBehaviour.Mode;
import dev.daybook.pspsim.payment.Payment;
import dev.daybook.pspsim.payment.PaymentStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The PSP's merchant-facing API: create a payment, look up its status. */
@RestController
class PaymentController {

  record CreatePaymentRequest(
      @NotBlank @Size(max = 100) String reference,
      @NotNull Payment.Direction direction,
      @NotNull @Positive Long amountMinor) {}

  private final PaymentStore payments;
  private final SimulatorBehaviour behaviour;
  private final Clock clock = Clock.systemUTC();

  PaymentController(PaymentStore payments, SimulatorBehaviour behaviour) {
    this.payments = payments;
    this.behaviour = behaviour;
  }

  /**
   * Creates a payment, idempotent by {@code reference}: repeating a request returns the original
   * payment (200) instead of moving money twice; reusing a reference for a different payment is a
   * 409.
   */
  @PostMapping("/v1/payments")
  ResponseEntity<?> create(@Valid @RequestBody CreatePaymentRequest request) {
    SimulatorBehaviour.Setting setting = behaviour.current();
    if (setting.mode() == Mode.DOWN) {
      return unavailable();
    }
    if (setting.mode() == Mode.SLOW) {
      pause(setting.delay()); // slow to decide; nothing recorded until it answers
    }

    Payment.Status outcome =
        setting.mode() == Mode.DECLINE ? Payment.Status.DECLINED : Payment.Status.SUCCEEDED;
    Payment candidate =
        new Payment(
            request.reference(),
            request.direction(),
            request.amountMinor(),
            outcome,
            clock.instant());
    PaymentStore.Recorded recorded = payments.recordIfAbsent(candidate);
    Payment stored = recorded.payment();

    if (!stored.sameRequestAs(request.direction(), request.amountMinor())) {
      ProblemDetail conflict =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.CONFLICT, "Reference already used for a different payment");
      return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
    }
    if (setting.mode() == Mode.LATE) {
      pause(setting.delay()); // decided and recorded, but the answer arrives too late
    }
    return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(stored);
  }

  @GetMapping("/v1/payments/{reference}")
  ResponseEntity<?> status(@PathVariable String reference) {
    if (behaviour.current().mode() == Mode.DOWN) {
      return unavailable();
    }
    return payments
        .find(reference)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such payment"));
  }

  private static ResponseEntity<ProblemDetail> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "PSP unavailable; request not accepted"));
  }

  private static void pause(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
