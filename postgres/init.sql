-- This script runs once when the PostgreSQL container is first initialised.
-- It creates the restricted application user (DML only) that the backend
-- runtime connection uses.  The migration user (POSTGRES_USER) already
-- exists at this point because Docker created it from POSTGRES_USER /
-- POSTGRES_PASSWORD env vars.
--
-- The DB_APP_USER and DB_APP_PASSWORD environment variables are substituted
-- by the shell entrypoint in docker-compose.yml via "envsubst".  If you run
-- this script manually, replace the placeholders accordingly.

\set app_user `echo "${DB_APP_USER:-chat_app_app}"`
\set app_password `echo "${DB_APP_PASSWORD:-change-this-app-password}"`

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') THEN
    EXECUTE format('CREATE USER %I WITH PASSWORD %L', :'app_user', :'app_password');
  END IF;
END$$;

GRANT CONNECT ON DATABASE chat_app TO :app_user;
GRANT USAGE ON SCHEMA public TO :app_user;

-- Grant DML on any tables that already exist (none yet, but safe to include).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :app_user;

-- Ensure tables created by future Flyway migrations are automatically accessible.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :app_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :app_user;
