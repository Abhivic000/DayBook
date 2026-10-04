package dev.daybook.api.ledger.application;

import dev.daybook.api.ledger.domain.Posting;
import dev.daybook.api.transaction.domain.Transaction;

/** Append-only: entries can be inserted, never updated or deleted (INV-4). */
public interface LedgerEntryRepository {

  void insert(Transaction transaction, Posting posting);
}
