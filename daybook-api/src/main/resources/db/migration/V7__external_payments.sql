-- Phase 3: money in and out through a payment service provider (PSP).

-- New per-tenant system accounts (ADR 0002, 0006):
--   PSP_SETTLEMENT         counterparty of money entering/leaving via the PSP; may go negative.
--   WITHDRAWAL_IN_TRANSIT  holds funds of withdrawals awaiting the PSP; the sum of open holds,
--                          so it can never legitimately be negative.
ALTER TABLE accounts DROP CONSTRAINT accounts_type_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_check
    CHECK (type IN ('USER', 'TREASURY', 'PSP_SETTLEMENT', 'WITHDRAWAL_IN_TRANSIT'));

ALTER TABLE accounts DROP CONSTRAINT accounts_user_not_negative_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_never_negative_types_check
    CHECK (type NOT IN ('USER', 'WITHDRAWAL_IN_TRANSIT') OR allow_negative = false);

-- Existing tenants get the new system accounts too.
INSERT INTO accounts (id, tenant_id, type, allow_negative, status)
SELECT gen_random_uuid(), t.id, 'PSP_SETTLEMENT', true, 'ACTIVE'
  FROM tenants t
 WHERE NOT EXISTS (SELECT 1 FROM accounts a WHERE a.tenant_id = t.id AND a.type = 'PSP_SETTLEMENT');

INSERT INTO accounts (id, tenant_id, type, allow_negative, status)
SELECT gen_random_uuid(), t.id, 'WITHDRAWAL_IN_TRANSIT', false, 'ACTIVE'
  FROM tenants t
 WHERE NOT EXISTS (SELECT 1 FROM accounts a WHERE a.tenant_id = t.id AND a.type = 'WITHDRAWAL_IN_TRANSIT');

-- Transactions that involve the PSP.
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'FUNDING', 'TOPUP', 'WITHDRAWAL', 'REVERSAL'));

ALTER TABLE transactions
    -- The customer's account. A PENDING top-up has no entries yet, so the transaction itself
    -- must record where the money goes when it settles.
    ADD COLUMN customer_account_id      uuid,
    -- Reference sent to the PSP as its idempotency key (= this transaction's id).
    ADD COLUMN psp_reference            text,
    ADD COLUMN failure_reason           text,
    -- Compensating transactions (ADR 0003): at most one reversal per original.
    ADD COLUMN reverses_transaction_id  uuid,
    ADD CONSTRAINT transactions_customer_account_fk
        FOREIGN KEY (tenant_id, customer_account_id) REFERENCES accounts (tenant_id, id),
    ADD CONSTRAINT transactions_reverses_fk
        FOREIGN KEY (tenant_id, reverses_transaction_id) REFERENCES transactions (tenant_id, id),
    ADD CONSTRAINT transactions_reverses_key UNIQUE (reverses_transaction_id),
    ADD CONSTRAINT transactions_psp_reference_key UNIQUE (psp_reference);

-- The sweeper's query: old PENDING transactions (Phase 3, step 4).
CREATE INDEX transactions_pending_idx ON transactions (created_at) WHERE status = 'PENDING';

-- "Final means final": once SETTLED or FAILED, a transaction's status can never change.
-- Unknown outcomes stay PENDING until the PSP is asked; decided outcomes are never flipped.
CREATE FUNCTION transactions_status_is_final() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status <> 'PENDING' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'transaction % is % and final; it cannot become %',
            OLD.id, OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transactions_status_is_final
    BEFORE UPDATE OF status ON transactions
    FOR EACH ROW EXECUTE FUNCTION transactions_status_is_final();
