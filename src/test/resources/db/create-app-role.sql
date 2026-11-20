-- Same attributes as infra/postgres/init/10-app-role.sh; V3 grants the privileges.
CREATE ROLE sensor_app LOGIN PASSWORD 'sensor_app_test_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
