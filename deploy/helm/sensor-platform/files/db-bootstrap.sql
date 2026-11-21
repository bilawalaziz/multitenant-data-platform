-- Idempotent database bootstrap. Runs as the master login (the RDS master user, or the superuser of local-data).
-- Every statement can run again: roles and databases are created only when missing, while attributes, passwords and
-- grants are asserted on every run so that a rotated secret reaches the database.
--
-- Matches infra/postgres/init/10-app-role.sh for the application role, and adds the Flyway owner:
--   owner    owns the database and the platform schema, runs the migrations (V3 grants the app role from here)
--   app      LOGIN, owns nothing, subject to row-level security (NOBYPASSRLS)
--   keycloak role and database of the identity provider
-- Passwords and names come from the environment (\getenv), never from the command line.
\set ON_ERROR_STOP on
\set QUIET on

\getenv owner_user OWNER_USER
\getenv owner_password OWNER_PASSWORD
\getenv app_user APP_USER
\getenv app_password APP_PASSWORD
\getenv kc_user KEYCLOAK_DB_USER
\getenv kc_password KEYCLOAK_DB_PASSWORD
\getenv db_name DB_NAME
\getenv kc_db KEYCLOAK_DB_NAME
\getenv with_keycloak KEYCLOAK_ENABLED

-- 1. roles: create when missing
SELECT set_config('bootstrap.roles', concat_ws(',', :'owner_user', :'app_user', CASE WHEN :'with_keycloak' = 'true' THEN :'kc_user' END), false) AS roles \gset
DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY string_to_array(current_setting('bootstrap.roles'), ',') LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', r);
        END IF;
    END LOOP;
END
$$;

-- 2. attributes and passwords, asserted on every run
ALTER ROLE :"owner_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'owner_password';
ALTER ROLE :"app_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';

-- 3. the owner database; CREATE DATABASE cannot run inside DO, so it is generated and executed conditionally.
-- On RDS the master is not a superuser: it needs SET on the role to create a database owned by it.
GRANT :"owner_user" TO CURRENT_USER WITH SET TRUE;
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'owner_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec
ALTER DATABASE :"db_name" OWNER TO :"owner_user";
REVOKE CONNECT ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"owner_user", :"app_user";

-- 4. Keycloak
\if :with_keycloak
ALTER ROLE :"kc_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'kc_password';
GRANT :"kc_user" TO CURRENT_USER WITH SET TRUE;
SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'kc_db') \gexec
ALTER DATABASE :"kc_db" OWNER TO :"kc_user";
REVOKE CONNECT ON DATABASE :"kc_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"kc_db" TO :"kc_user";
\endif

\echo database bootstrap finished
