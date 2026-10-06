-- Statement projection (ADR 0011), owned by the consumer's role in the
-- projection schema. Built only from AccountEntryPosted events (ADR 0010).

-- Latest applied state per account: the version is how duplicates and gaps are
-- detected, the balance is what reconciliation compares with the ledger.
CREATE TABLE projection.account_balances (
    account_id     uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    account_type   text        NOT NULL,
    last_version   bigint      NOT NULL,
    balance_minor  bigint      NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX account_balances_tenant_idx ON projection.account_balances (tenant_id);

-- One line per applied event, with the running balance the ledger cannot
-- cheaply produce. Doubles as the "already processed" record: an event can be
-- stored only once (event_id), and only one event per account version.
CREATE TABLE projection.statement_lines (
    event_id             uuid        PRIMARY KEY,
    tenant_id            uuid        NOT NULL,
    account_id           uuid        NOT NULL,
    account_version      bigint      NOT NULL,
    transaction_id       uuid        NOT NULL,
    transaction_type     text        NOT NULL,
    direction            text        NOT NULL,
    amount_minor         bigint      NOT NULL,
    balance_after_minor  bigint      NOT NULL,
    occurred_at          timestamptz NOT NULL,
    correlation_id       text,
    projected_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT statement_lines_account_version_key UNIQUE (account_id, account_version),
    CONSTRAINT statement_lines_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT statement_lines_amount_check CHECK (amount_minor > 0)
);
