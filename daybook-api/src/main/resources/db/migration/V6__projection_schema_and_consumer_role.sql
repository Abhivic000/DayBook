-- The consumer's own database role and schema (ADR 0005, 0011, 0013).
--
-- daybook_consumer owns the projection schema and has no privileges on the
-- ledger tables in public. The API (the role running this migration) gets
-- read-only access to projection tables, never write access.
--
-- The password below is a Flyway placeholder filled from configuration
-- (DAYBOOK_CONSUMER_DB_PASSWORD), so no password is stored in this file.
-- (Placeholders are substituted everywhere, comments included — don't name one here.)

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'daybook_consumer') THEN
        CREATE ROLE daybook_consumer LOGIN PASSWORD '${consumer-password}';
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS projection AUTHORIZATION daybook_consumer;

-- The API may look inside the schema and read every table the consumer creates
-- in it, now or later — but not insert, update or delete.
GRANT USAGE ON SCHEMA projection TO CURRENT_USER;
ALTER DEFAULT PRIVILEGES FOR ROLE daybook_consumer IN SCHEMA projection
    GRANT SELECT ON TABLES TO CURRENT_USER;
