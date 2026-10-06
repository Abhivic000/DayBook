package dev.daybook.consumer.statement.application;

import dev.daybook.consumer.statement.domain.AccountEntryEvent;
import dev.daybook.consumer.statement.domain.EventGapException;
import dev.daybook.consumer.statement.domain.ProjectedAccount;
import dev.daybook.consumer.statement.domain.ProjectionMismatchException;
import dev.daybook.consumer.statement.domain.StatementProjection;
import dev.daybook.consumer.statement.domain.StatementProjection.Decision;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one event to the projection in one database transaction (FR-9.1, FR-9.2).
 *
 * <p>The Kafka offset is committed only after this returns, i.e. after the transaction committed. A
 * crash in between redelivers the event, which is then recognised as a duplicate — so every event
 * takes effect exactly once even though delivery is at-least-once.
 */
@Service
public class StatementProjector {

  /** What happened to an event. */
  public enum Outcome {
    APPLIED,
    DUPLICATE
  }

  private final StatementRepository statements;

  StatementProjector(StatementRepository statements) {
    this.statements = statements;
  }

  /**
   * Applies {@code event} if it is the account's next one.
   *
   * @throws EventGapException an earlier event for the account is missing
   * @throws ProjectionMismatchException the event's balance contradicts the projection's history
   */
  @Transactional
  public Outcome project(AccountEntryEvent event) {
    ProjectedAccount current =
        statements.lockAccount(event.accountId()).orElse(ProjectedAccount.UNSEEN);
    Decision decision = StatementProjection.decide(current, event);
    return switch (decision) {
      case Decision.Apply apply -> {
        statements.apply(event);
        yield Outcome.APPLIED;
      }
      case Decision.Duplicate duplicate -> Outcome.DUPLICATE;
      case Decision.Gap gap ->
          throw new EventGapException(
              event.accountId(), gap.expectedVersion(), event.accountVersion());
      case Decision.Mismatch mismatch ->
          throw new ProjectionMismatchException(
              event.accountId(),
              event.accountVersion(),
              mismatch.expectedBalanceMinor(),
              event.balanceAfterMinor());
    };
  }
}
