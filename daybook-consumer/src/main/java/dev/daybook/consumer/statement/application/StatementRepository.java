package dev.daybook.consumer.statement.application;

import dev.daybook.consumer.statement.domain.AccountEntryEvent;
import dev.daybook.consumer.statement.domain.ProjectedAccount;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {

  /** The account's projected state, row-locked until the transaction ends; empty if unseen. */
  Optional<ProjectedAccount> lockAccount(UUID accountId);

  /** Records the event as a statement line and advances the account's version and balance. */
  void apply(AccountEntryEvent event);
}
