-- Flyway callback, re-applied after every migration run (ADR-026): least-privilege grants for the application role.
-- Migrations run as the schema owner; the application connects as ${app_role}, which may read and write data but
-- never change the schema, truncate tables, or rewrite append-only history. A no-op when app_role is not set.
DO $$
DECLARE
    app text := '${app_role}';
BEGIN
    IF app = '' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RETURN;
    END IF;
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', app);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', app);
    EXECUTE format('REVOKE TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public FROM %I', app);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app);
    EXECUTE format('REVOKE UPDATE, DELETE ON ledger_transactions, ledger_entries, audit_log, payment_transitions, mandate_transitions FROM %I', app);
    EXECUTE format('REVOKE ALL ON flyway_schema_history FROM %I', app);
END
$$;
