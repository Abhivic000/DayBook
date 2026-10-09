package dev.daybook.api.funding.application;

import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.PspOperationResult.Outcome;
import dev.daybook.api.transaction.domain.TransactionStatus;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase two of a top-up or withdrawal: ask the PSP to move the money, then record what it said. No
 * database transaction is open during the PSP call; each recording step is its own short
 * transaction in {@link PspTransactionResolver}.
 */
@Component
class PspPayments {

  private static final Logger log = LoggerFactory.getLogger(PspPayments.class);

  private final PspGateway psp;
  private final PspTransactionResolver resolver;

  PspPayments(PspGateway psp, PspTransactionResolver resolver) {
    this.psp = psp;
    this.resolver = resolver;
  }

  PspOperationResult execute(
      UUID tenantId, UUID transactionId, PspGateway.Direction direction, Money amount) {
    PspGateway.Outcome outcome = psp.createPayment(transactionId.toString(), direction, amount);
    Outcome result =
        switch (outcome) {
          case PspGateway.Outcome.Succeeded succeeded ->
              record(transactionId, () -> resolver.settle(tenantId, transactionId));
          case PspGateway.Outcome.Declined declined ->
              record(
                  transactionId, () -> resolver.fail(tenantId, transactionId, "payment-declined"));
          case PspGateway.Outcome.NotAccepted notAccepted -> {
            Outcome failed =
                record(
                    transactionId,
                    () ->
                        resolver.fail(
                            tenantId, transactionId, "psp-not-accepted: " + notAccepted.reason()));
            yield failed == Outcome.DECLINED ? Outcome.NOT_ACCEPTED : failed;
          }
          case PspGateway.Outcome.Unknown unknown -> {
            log.warn(
                "PSP outcome for {} unknown ({}); left PENDING", transactionId, unknown.reason());
            yield Outcome.PENDING;
          }
        };
    return new PspOperationResult(result, transactionId);
  }

  /**
   * Records an outcome. If the ledger cannot accept it right now (e.g. the customer's account was
   * frozen between the phases), nothing is guessed: the transaction stays PENDING, visible to
   * reconciliation and to an operator.
   */
  private static Outcome record(UUID transactionId, Supplier<TransactionStatus> resolution) {
    try {
      return switch (resolution.get()) {
        case SETTLED -> Outcome.SETTLED;
        case FAILED -> Outcome.DECLINED;
        case PENDING -> Outcome.PENDING;
      };
    } catch (DomainException e) {
      log.error("PSP outcome for {} could not be recorded: {}", transactionId, e.getMessage());
      return Outcome.PENDING;
    }
  }
}
