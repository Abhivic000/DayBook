package dev.daybook.api.ledger.application;

import dev.daybook.api.account.domain.Account;
import dev.daybook.api.common.web.CorrelationIdFilter;
import dev.daybook.api.ledger.domain.Ledger;
import dev.daybook.api.outbox.application.OutboxRepository;
import dev.daybook.api.outbox.domain.OutboxMessage;
import dev.daybook.api.transaction.domain.Transaction;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes one {@link AccountEntryPosted} outbox message per applied ledger entry (ADR 0010). */
@Component
class AccountEntryEventWriter {

  private final OutboxRepository outbox;
  private final JsonMapper json;
  private final Clock clock;
  private final LedgerEventProperties properties;

  AccountEntryEventWriter(
      OutboxRepository outbox, JsonMapper json, Clock clock, LedgerEventProperties properties) {
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
    this.properties = properties;
  }

  /** Must run in the same database transaction that posted the entries. */
  void record(Transaction transaction, List<Ledger.AppliedEntry> trail) {
    Instant now = clock.instant();
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    for (Ledger.AppliedEntry applied : trail) {
      Account account = applied.accountAfter();
      UUID eventId = UUID.randomUUID();
      AccountEntryPosted event =
          new AccountEntryPosted(
              eventId,
              AccountEntryPosted.TYPE,
              now,
              transaction.tenantId(),
              correlationId,
              account.id(),
              account.type(),
              account.version(),
              transaction.id(),
              transaction.type(),
              applied.entry().direction(),
              applied.entry().amount().minor(),
              account.balance().minor());
      outbox.append(
          new OutboxMessage(
              eventId,
              AccountEntryPosted.TYPE,
              properties.accountEntriesTopic(),
              account.id().toString(),
              json.writeValueAsString(event),
              correlationId));
    }
  }
}
