-- Transactional outbox (FR-8). Rows are inserted in the same database
-- transaction as the ledger entries they describe, then published to Kafka by
-- the relay. A crash between commit and publish therefore cannot lose an event.

CREATE TABLE outbox_events (
    -- Monotonic, so the relay can publish in insertion order. For one account,
    -- inserts happen under that account's row lock, so its ids follow commit order.
    id              bigserial   PRIMARY KEY,
    event_id        uuid        NOT NULL,
    event_type      text        NOT NULL,
    topic           text        NOT NULL,
    message_key     text        NOT NULL,
    -- text, not jsonb: published byte-for-byte as written.
    payload         text        NOT NULL,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Set only after the broker acknowledges (FR-8.3).
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text,

    CONSTRAINT outbox_events_event_id_key UNIQUE (event_id)
);

-- Partial index: covers only unpublished rows, so the relay's query stays cheap
-- no matter how many published rows accumulate.
CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events (id)
    WHERE published_at IS NULL;

-- Purge of old published rows (FR-8.6).
CREATE INDEX outbox_events_published_at_idx
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;
