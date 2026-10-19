-- Trace context across the outbox (FR-11.4).
--
-- The request that writes an event and the relay that publishes it run on different threads at
-- different times, so the trace would otherwise end at the outbox. Each row keeps the W3C
-- traceparent of the request that wrote it; the relay continues that trace when publishing.
-- NULL when the writer was not being traced (and for rows written before this migration).

ALTER TABLE outbox_events
    ADD COLUMN traceparent text
        CONSTRAINT outbox_events_traceparent_length_check CHECK (length(traceparent) <= 256);
