package dev.daybook.pspsim.payment;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Payments in memory, keyed by the caller's reference. Restarting the simulator forgets them —
 * deliberately, since a caller must already cope with "payment not found" (ADR 0015).
 */
@Component
public class PaymentStore {

  private final Map<String, Payment> payments = new ConcurrentHashMap<>();

  /**
   * The outcome of {@link #recordIfAbsent}.
   *
   * @param payment the payment now stored under the reference
   * @param created whether this call created it (false: it already existed)
   */
  public record Recorded(Payment payment, boolean created) {}

  /**
   * Stores {@code payment} unless one with the same reference exists. Atomic, so two concurrent
   * requests with one reference create one payment.
   */
  public Recorded recordIfAbsent(Payment payment) {
    Payment existing = payments.putIfAbsent(payment.reference(), payment);
    return existing != null ? new Recorded(existing, false) : new Recorded(payment, true);
  }

  public Optional<Payment> find(String reference) {
    return Optional.ofNullable(payments.get(reference));
  }

  public void clear() {
    payments.clear();
  }
}
