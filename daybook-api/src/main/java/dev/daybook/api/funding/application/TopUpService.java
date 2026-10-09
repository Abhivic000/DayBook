package dev.daybook.api.funding.application;

import dev.daybook.api.account.application.AccountService;
import dev.daybook.api.account.domain.Account;
import dev.daybook.api.account.domain.AccountNotActiveException;
import dev.daybook.api.account.domain.AccountStatus;
import dev.daybook.api.common.domain.DomainException;
import dev.daybook.api.common.domain.Money;
import dev.daybook.api.funding.application.PspOperationResult.Outcome;
import dev.daybook.api.ledger.application.LedgerProperties;
import dev.daybook.api.ledger.domain.AmountLimitExceededException;
import dev.daybook.api.transaction.application.TransactionRepository;
import dev.daybook.api.transaction.domain.Transaction;
import dev.daybook.api.transaction.domain.TransactionStatus;
import dev.daybook.api.transaction.domain.TransactionType;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top-up: money in from the PSP to a USER account (FR-6), in two phases with the PSP call between
 * them and no database transaction open during it.
 */
@Service
public class TopUpService {

  private static final Logger log = LoggerFactory.getLogger(TopUpService.class);

  /** A top-up request. */
  public record TopUpCommand(UUID tenantId, UUID accountId, Money amount) {
    public TopUpCommand {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(amount, "amount");
      if (!amount.isPositive()) {
        throw new IllegalArgumentException("Top-up amount must be positive");
      }
    }
  }

  private final AccountService accounts;
  private final TransactionRepository transactions;
  private final PspGateway psp;
  private final PspTransactionResolver resolver;
  private final Money maxAmount;

  TopUpService(
      AccountService accounts,
      TransactionRepository transactions,
      PspGateway psp,
      PspTransactionResolver resolver,
      LedgerProperties ledger) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.psp = psp;
    this.resolver = resolver;
    this.maxAmount = Money.ofMinor(ledger.maxTransactionAmountMinor());
  }

  /**
   * Phase one: validate and record a PENDING top-up. Runs inside the caller's transaction (which
   * also claims the idempotency key) and commits before the PSP is contacted, so a crash during the
   * PSP call leaves a durable record to resolve.
   *
   * @throws PspUnavailableException the circuit breaker is open — before anything is written
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID begin(TopUpCommand command) {
    if (!psp.acceptingRequests()) {
      throw new PspUnavailableException("circuit breaker open");
    }
    if (command.amount().compareTo(maxAmount) > 0) {
      throw new AmountLimitExceededException(command.amount(), maxAmount);
    }
    Account account = accounts.getUserAccount(command.tenantId(), command.accountId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new AccountNotActiveException(account.id(), account.status());
    }
    Transaction pending =
        Transaction.pendingWithPsp(
            UUID.randomUUID(),
            command.tenantId(),
            TransactionType.TOPUP,
            command.amount(),
            command.accountId());
    transactions.insert(pending);
    return pending.id();
  }

  /** Phase two: ask the PSP, then record the outcome. No database transaction spans the call. */
  public PspOperationResult complete(TopUpCommand command, UUID transactionId) {
    PspGateway.Outcome outcome =
        psp.createPayment(transactionId.toString(), PspGateway.Direction.COLLECT, command.amount());
    return new PspOperationResult(apply(command.tenantId(), transactionId, outcome), transactionId);
  }

  private Outcome apply(UUID tenantId, UUID transactionId, PspGateway.Outcome outcome) {
    return switch (outcome) {
      case PspGateway.Outcome.Succeeded succeeded -> settle(tenantId, transactionId);
      case PspGateway.Outcome.Declined declined ->
          toOutcome(resolver.fail(tenantId, transactionId, "payment-declined"));
      case PspGateway.Outcome.NotAccepted notAccepted ->
          resolver.fail(tenantId, transactionId, "psp-not-accepted: " + notAccepted.reason())
                  == TransactionStatus.FAILED
              ? Outcome.NOT_ACCEPTED
              : Outcome.PENDING;
      case PspGateway.Outcome.Unknown unknown -> {
        log.warn("Top-up {} outcome unknown ({}); left PENDING", transactionId, unknown.reason());
        yield Outcome.PENDING;
      }
    };
  }

  private Outcome settle(UUID tenantId, UUID transactionId) {
    try {
      return toOutcome(resolver.settleTopUp(tenantId, transactionId));
    } catch (DomainException e) {
      // The PSP has the customer's money but the ledger cannot accept it (e.g. account frozen
      // meanwhile). Never guess: leave it PENDING and visible for reconciliation.
      log.error(
          "Top-up {} collected by PSP but could not be credited: {}",
          transactionId,
          e.getMessage());
      return Outcome.PENDING;
    }
  }

  private static Outcome toOutcome(TransactionStatus status) {
    return switch (status) {
      case SETTLED -> Outcome.SETTLED;
      case FAILED -> Outcome.DECLINED;
      case PENDING -> Outcome.PENDING;
    };
  }
}
