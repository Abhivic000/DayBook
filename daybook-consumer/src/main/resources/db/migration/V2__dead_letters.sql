-- Dead letters, copied from the DLQ topic so an operator can inspect them over
-- HTTP (FR-9.4, ADR 0012). DLQ depth = number of rows.

CREATE TABLE projection.dead_letters (
    id                  bigserial   PRIMARY KEY,
    -- Where the failed record originally lived. Unique, so a record that reaches
    -- the DLQ twice (at-least-once, again) is still recorded once.
    original_topic      text        NOT NULL,
    original_partition  integer     NOT NULL,
    original_offset     bigint      NOT NULL,
    message_key         text,
    payload             text,
    exception_class     text,
    exception_message   text,
    correlation_id      text,
    failed_at           timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT dead_letters_original_record_key
        UNIQUE (original_topic, original_partition, original_offset)
);

CREATE INDEX dead_letters_recorded_at_idx ON projection.dead_letters (recorded_at DESC);
