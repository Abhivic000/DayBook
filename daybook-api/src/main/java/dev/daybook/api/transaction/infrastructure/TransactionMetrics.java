package dev.daybook.api.transaction.infrastructure;

import dev.daybook.api.common.metrics.AfterCommit;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code daybook.transactions{type,status}} (FR-11.3): one count each time a transaction reaches a
 * status — created PENDING, then SETTLED or FAILED, or created directly SETTLED. Counted after
 * commit, so rolled-back work never shows up.
 */
@Component
class TransactionMetrics {

  private final MeterRegistry registry;

  TransactionMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void reached(TransactionType type, TransactionStatus status) {
    Counter counter =
        Counter.builder("daybook.transactions")
            .description("Transactions reaching each status, counted after commit")
            .tag("type", type.name())
            .tag("status", status.name())
            .register(registry);
    AfterCommit.run(counter::increment);
  }
}
