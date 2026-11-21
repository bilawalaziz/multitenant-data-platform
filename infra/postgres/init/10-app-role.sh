#!/bin/sh
# Runs once, when the Postgres data volume is first created. Creates the login role the application uses at runtime.
# It owns nothing and cannot bypass row-level security; Flyway (V3) grants it the table privileges it needs.
# An existing volume is not re-initialised: create the role by hand (see the row-level security notes).
set -eu

: "${DB_APP_PASSWORD:?DB_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="${DB_APP_USER:-sensor_app}" -v app_password="$DB_APP_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SQL
