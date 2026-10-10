package dev.daybook.api.funding.application;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.funding.application.PspOperationResult.Outcome;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.application.TransactionRepository.PendingTransaction;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves top-ups and withdrawals left PENDING because their PSP outcome was unknown (FR-6.5, ADR
 * 0016): asks the PSP, and settles or fails accordingly. "Not found" and "PSP unreachable" are not
 * answers — those transactions stay PENDING, and past {@code maxPendingAge} are flagged as stale,
 * never guessed.
 *
 * <p>Needs no leader election: every resolution takes the transaction's row lock and re-checks it
 * is still PENDING, and status lookups are read-only, so two instances sweeping at once is merely
 * redundant, never wrong.
 */
@Service
public class PendingTransactionSweeper {

  private static final Logger log = LoggerFactory.getLogger(PendingTransactionSweeper.class);

  /** What one sweep did. {@code stale}: still PENDING and older than the maximum age. */
  public record SweepReport(int examined, int settled, int failed, int stillPending, int stale) {}

  private final TransactionRepository transactions;
  private final PspGateway psp;
  private final PspTransactionResolver resolver;
  private final ResolvedTransactionListener listener;
  private final SweeperProperties properties;
  private final Clock clock;

  PendingTransactionSweeper(
      TransactionRepository transactions,
      PspGateway psp,
      PspTransactionResolver resolver,
      ResolvedTransactionListener listener,
      SweeperProperties properties,
      Clock clock) {
    this.transactions = transactions;
    this.psp = psp;
    this.resolver = resolver;
    this.listener = listener;
    this.properties = properties;
    this.clock = clock;
  }

  public SweepReport sweep() {
    List<PendingTransaction> due =
        transactions.findPendingWithPsp(properties.minAge(), properties.batchSize());
    int settled = 0;
    int failed = 0;
    int stillPending = 0;
    int stale = 0;
    Instant staleBefore = clock.instant().minus(properties.maxPendingAge());

    for (PendingTransaction pending : due) {
      TransactionStatus status = resolve(pending.transaction());
      switch (status) {
        case SETTLED -> settled++;
        case FAILED -> failed++;
        case PENDING -> {
          stillPending++;
          if (pending.createdAt().isBefore(staleBefore)) {
            stale++;
            log.error(
                "Transaction {} has been PENDING since {}; the PSP cannot confirm its outcome."
                    + " Needs investigation — it will not be resolved automatically.",
                pending.transaction().id(),
                pending.createdAt());
          }
        }
      }
    }
    SweepReport report = new SweepReport(due.size(), settled, failed, stillPending, stale);
    if (!due.isEmpty()) {
      log.info("Sweep: {}", report);
    }
    return report;
  }

  /** Asks the PSP about one transaction and applies the answer. Never throws: one bad apple. */
  private TransactionStatus resolve(Transaction transaction) {
    try {
      PspGateway.PaymentStatus answer = psp.paymentStatus(transaction.pspReference());
      TransactionStatus status =
          switch (answer) {
            case PspGateway.PaymentStatus.Succeeded s ->
                resolver.settle(transaction.tenantId(), transaction.id());
            case PspGateway.PaymentStatus.Declined d ->
                resolver.fail(transaction.tenantId(), transaction.id(), "payment-declined");
            case PspGateway.PaymentStatus.NotFound n -> TransactionStatus.PENDING;
            case PspGateway.PaymentStatus.Unavailable u -> TransactionStatus.PENDING;
          };
      if (status != TransactionStatus.PENDING) {
        listener.resolved(
            transaction.tenantId(),
            transaction.id(),
            status == TransactionStatus.SETTLED ? Outcome.SETTLED : Outcome.DECLINED);
      }
      return status;
    } catch (DomainException e) {
      log.error("Could not record PSP outcome for {}: {}", transaction.id(), e.getMessage());
      return TransactionStatus.PENDING;
    } catch (RuntimeException e) {
      log.error("Sweeping transaction {} failed; will retry next run", transaction.id(), e);
      return TransactionStatus.PENDING;
    }
  }
}
