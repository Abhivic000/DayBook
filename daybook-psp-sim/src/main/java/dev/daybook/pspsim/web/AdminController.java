package dev.daybook.pspsim.web;

import dev.daybook.pspsim.behaviour.SimulatorBehaviour;
import dev.daybook.pspsim.behaviour.SimulatorBehaviour.Mode;
import dev.daybook.pspsim.payment.PaymentStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Switches the simulator's behaviour, e.g. from scripts or the chaos demo:
 *
 * <pre>
 * PUT /admin/behaviour {"mode":"LATE","delayMs":5000}
 * </pre>
 *
 * <p>Unauthenticated on purpose: the simulator stands in for an external company and only ever runs
 * locally or in CI.
 */
@RestController
class AdminController {

  record BehaviourRequest(@NotNull Mode mode, @Nullable @PositiveOrZero Long delayMs) {}

  record BehaviourResponse(Mode mode, long delayMs) {
    static BehaviourResponse of(SimulatorBehaviour.Setting setting) {
      return new BehaviourResponse(setting.mode(), setting.delay().toMillis());
    }
  }

  private final SimulatorBehaviour behaviour;
  private final PaymentStore payments;

  AdminController(SimulatorBehaviour behaviour, PaymentStore payments) {
    this.behaviour = behaviour;
    this.payments = payments;
  }

  @GetMapping("/admin/behaviour")
  BehaviourResponse current() {
    return BehaviourResponse.of(behaviour.current());
  }

  @PutMapping("/admin/behaviour")
  BehaviourResponse change(@Valid @RequestBody BehaviourRequest request) {
    Duration delay =
        request.delayMs() != null
            ? Duration.ofMillis(request.delayMs())
            : SimulatorBehaviour.defaultDelay(request.mode());
    behaviour.set(new SimulatorBehaviour.Setting(request.mode(), delay));
    return BehaviourResponse.of(behaviour.current());
  }

  /** Forgets all payments, as a simulator restart would. */
  @DeleteMapping("/admin/payments")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void forgetPayments() {
    payments.clear();
  }
}
