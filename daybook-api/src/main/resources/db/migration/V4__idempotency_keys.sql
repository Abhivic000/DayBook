-- Idempotency records (FR-5, ADR 0007). The unique constraint on
-- (tenant_id, idempotency_key) is the concurrency control: two concurrent
-- requests with the same key cannot both insert.

CREATE TABLE idempotency_keys (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    idempotency_key  text        NOT NULL,
    -- SHA-256 of the canonicalised request (method, path and body).
    request_hash     text        NOT NULL,
    status           text        NOT NULL,
    response_status  integer,
    -- text, not jsonb: jsonb normalises key order and whitespace, and the
    -- stored response must be replayed byte-for-byte.
    response_body    text,
    transaction_id   uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_tenant_key_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT idempotency_keys_transaction_fk
        FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id),
    CONSTRAINT idempotency_keys_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_keys_key_length_check
        CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT idempotency_keys_completed_has_response_check
        CHECK (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);

-- Purge job scans by expiry (FR-5.7).
CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
