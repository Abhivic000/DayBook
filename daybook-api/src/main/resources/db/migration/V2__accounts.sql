-- Accounts (ADR 0002). balance_minor is a cache of the sum of the account's
-- ledger entries; the entries are the source of truth (INV-3).

CREATE TABLE accounts (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    type            text        NOT NULL,
    balance_minor   bigint      NOT NULL DEFAULT 0,
    version         bigint      NOT NULL DEFAULT 0,
    allow_negative  boolean     NOT NULL,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- Target of composite foreign keys, so child rows can be forced to share
    -- the account's tenant. Also serves as the tenant_id index.
    CONSTRAINT accounts_tenant_id_id_key UNIQUE (tenant_id, id),

    CONSTRAINT accounts_type_check CHECK (type IN ('USER', 'TREASURY')),
    CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'FROZEN')),

    -- User accounts can never be configured to go negative (ADR 0002).
    CONSTRAINT accounts_user_not_negative_check
        CHECK (type <> 'USER' OR allow_negative = false),

    -- INV-6, enforced by the database rather than only by application code.
    CONSTRAINT accounts_balance_check
        CHECK (allow_negative OR balance_minor >= 0)
);

-- Exactly one of each system account type per tenant.
CREATE UNIQUE INDEX accounts_one_system_account_per_type_idx
    ON accounts (tenant_id, type)
    WHERE type <> 'USER';
