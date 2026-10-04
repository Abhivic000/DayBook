-- Transactions and the double-entry ledger.

CREATE TABLE transactions (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    type          text        NOT NULL,
    status        text        NOT NULL,
    amount_minor  bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT transactions_tenant_id_id_key UNIQUE (tenant_id, id),
    CONSTRAINT transactions_type_check CHECK (type IN ('TRANSFER', 'FUNDING')),
    CONSTRAINT transactions_status_check CHECK (status IN ('PENDING', 'SETTLED', 'FAILED')),
    CONSTRAINT transactions_amount_check CHECK (amount_minor > 0)
);

CREATE TABLE ledger_entries (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    transaction_id  uuid        NOT NULL,
    account_id      uuid        NOT NULL,
    direction       text        NOT NULL,
    -- Always positive; direction carries the sign.
    amount_minor    bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- Composite keys: an entry's transaction and account must both belong to
    -- the entry's tenant. Cross-tenant postings are impossible at the DB level.
    CONSTRAINT ledger_entries_transaction_fk
        FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id),
    CONSTRAINT ledger_entries_account_fk
        FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id),

    CONSTRAINT ledger_entries_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_check CHECK (amount_minor > 0)
);

CREATE INDEX ledger_entries_transaction_id_idx ON ledger_entries (transaction_id);

-- Account statements, newest first. id breaks ties: every entry written in one
-- database transaction shares the same now(), so created_at alone is not a
-- stable cursor.
CREATE INDEX ledger_entries_account_statement_idx
    ON ledger_entries (account_id, created_at DESC, id DESC);


-- INV-4: append-only. Rejects UPDATE, DELETE and TRUNCATE regardless of what
-- application code attempts.
CREATE FUNCTION ledger_entries_reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER ledger_entries_no_update_or_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_reject_modification();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_reject_modification();


-- INV-1: every transaction's debits equal its credits. Deferred, so it runs
-- at COMMIT — after all of a transaction's entries have been inserted —
-- rather than after the first entry, when the transaction is necessarily
-- still unbalanced.
CREATE FUNCTION ledger_entries_assert_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    net bigint;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO net
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF net <> 0 THEN
        RAISE EXCEPTION 'transaction % is unbalanced: debits minus credits = %',
            NEW.transaction_id, net;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_assert_balanced();
