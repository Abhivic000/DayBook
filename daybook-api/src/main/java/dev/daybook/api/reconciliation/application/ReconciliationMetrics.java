package dev.daybook.api.reconciliation.application;

import dev.daybook.api.reconciliation.domain.Check;
import dev.daybook.api.reconciliation.domain.ReconciliationReport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Exposes the latest completed run as gauges (FR-10.4): {@code
 * daybook.reconciliation.violations{check=...}} per check, and the completion time of the last run.
 * A non-zero CRITICAL value is an alertable condition (Phase 5 wires the alert).
 */
@Component
class ReconciliationMetrics {

  private final Map<Check, AtomicLong> violations = new EnumMap<>(Check.class);
  private final AtomicLong lastCompletedEpochSeconds = new AtomicLong();

  ReconciliationMetrics(MeterRegistry registry) {
    for (Check check : Check.values()) {
      AtomicLong count = new AtomicLong();
      violations.put(check, count);
      Gauge.builder("daybook.reconciliation.violations", count, AtomicLong::get)
          .description("Violations found by the latest completed reconciliation run")
          .tag("check", check.name())
          .tag("severity", check.severity().name())
          .register(registry);
    }
    Gauge.builder(
            "daybook.reconciliation.last.completed", lastCompletedEpochSeconds, AtomicLong::get)
        .description("When the latest reconciliation run completed (epoch seconds)")
        .baseUnit("seconds")
        .register(registry);
  }

  void record(ReconciliationReport report) {
    if (report.status() != ReconciliationReport.Status.COMPLETED) {
      return; // a failed run proves nothing: keep the last known values
    }
    for (Check check : Check.values()) {
      violations.get(check).set(report.count(check));
    }
    lastCompletedEpochSeconds.set(report.finishedAt().getEpochSecond());
  }
}
