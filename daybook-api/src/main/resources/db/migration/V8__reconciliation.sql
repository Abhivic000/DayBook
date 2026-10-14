-- Reconciliation reports (FR-10.3, ADR 0018). Written only by the reconciliation
-- job, after its read-only snapshot of the ledger has been checked.

CREATE TABLE reconciliation_runs (
    id               uuid        PRIMARY KEY,
    trigger          text        NOT NULL,
    status           text        NOT NULL,
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz NOT NULL,
    checks_run       integer     NOT NULL,
    violation_count  integer     NOT NULL,
    error            text,

    CONSTRAINT reconciliation_runs_trigger_check CHECK (trigger IN ('SCHEDULED', 'ON_DEMAND')),
    CONSTRAINT reconciliation_runs_status_check CHECK (status IN ('COMPLETED', 'FAILED'))
);

CREATE INDEX reconciliation_runs_started_at_idx ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_violations (
    id          bigserial PRIMARY KEY,
    run_id      uuid      NOT NULL REFERENCES reconciliation_runs (id) ON DELETE CASCADE,
    check_name  text      NOT NULL,
    severity    text      NOT NULL,
    tenant_id   uuid,
    -- The account or transaction the violation is about, when there is one.
    subject_id  uuid,
    detail      text      NOT NULL,

    CONSTRAINT reconciliation_violations_severity_check CHECK (severity IN ('CRITICAL', 'WARNING'))
);

CREATE INDEX reconciliation_violations_run_idx ON reconciliation_violations (run_id);
