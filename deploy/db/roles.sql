-- One-time database bootstrap (ADR-026), run by the database administrator as the master user, e.g.
--   psql "$MASTER_URL" -v migrator_password="$MIGRATOR_PASSWORD" -v app_password="$APP_PASSWORD" -f roles.sql
-- Terraform stores both passwords in Secrets Manager; nothing here is committed with a real value.
\set ON_ERROR_STOP on

CREATE ROLE gateway_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE gateway_app LOGIN PASSWORD :'app_password' CONNECTION LIMIT 400;

-- The migrator owns the schema and everything in it; the app only gets what afterMigrate.sql grants.
-- (Role names cannot start with pg_, which PostgreSQL reserves.)
GRANT CONNECT ON DATABASE payments TO gateway_migrator, gateway_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO gateway_migrator;
ALTER SCHEMA public OWNER TO gateway_migrator;
GRANT USAGE ON SCHEMA public TO gateway_app;

-- Guard rails for the application sessions (Aurora additionally enforces TLS with rds.force_ssl = 1).
ALTER ROLE gateway_app SET statement_timeout = '30s';
ALTER ROLE gateway_app SET idle_in_transaction_session_timeout = '60s';
