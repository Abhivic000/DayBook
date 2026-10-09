package dev.daybook.pspsim.behaviour;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** How the simulator currently behaves. Switched at runtime via the admin endpoint. */
@Component
public class SimulatorBehaviour {

  /** Failure modes on demand (ADR 0015). */
  public enum Mode {
    /** Succeed immediately. */
    APPROVE,
    /** Decline immediately: a definitive failure. */
    DECLINE,
    /** Wait {@code delay}, then succeed. */
    SLOW,
    /**
     * Record the payment as succeeded, then wait {@code delay} before answering — longer than the
     * caller will wait. The caller times out without knowing the outcome; a later status query
     * reveals it.
     */
    LATE,
    /** Answer 503 and record nothing: provably not accepted, so safe to retry. */
    DOWN
  }

  /** The current mode and its delay (used by SLOW and LATE). */
  public record Setting(Mode mode, Duration delay) {
    public Setting {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(delay, "delay");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must not be negative");
      }
    }
  }

  private final AtomicReference<Setting> current =
      new AtomicReference<>(new Setting(Mode.APPROVE, Duration.ZERO));

  public Setting current() {
    return current.get();
  }

  public void set(Setting setting) {
    current.set(setting);
  }

  /** Default delays when none is given: SLOW stays within a 2s caller timeout, LATE exceeds it. */
  public static Duration defaultDelay(Mode mode) {
    return switch (mode) {
      case SLOW -> Duration.ofSeconds(1);
      case LATE -> Duration.ofSeconds(5);
      case APPROVE, DECLINE, DOWN -> Duration.ZERO;
    };
  }
}
