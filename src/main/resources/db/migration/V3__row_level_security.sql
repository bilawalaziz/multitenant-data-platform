-- Row-level security for every tenant-owned table. The application connects as ${app_role}, which owns nothing and
-- cannot bypass policies; this migration runs as the owner role, which is why Flyway uses separate credentials.
--
-- The tenant comes from the session setting app.tenant_id, set by the application on every connection checkout.
-- With no setting (or an empty one) the tenant is NULL, no row matches and nothing is returned or written.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_role}') THEN
        RAISE EXCEPTION 'Database role "${app_role}" does not exist. Create it before migrating (see infra/postgres/init/10-app-role.sh), or set the Flyway placeholder app_role to an existing role.';
    END IF;
END
$$;

CREATE FUNCTION platform.current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;

-- Cross-tenant work (outbox publishing, retention, administration) opts in with app.rls_system = 'true'.
CREATE FUNCTION platform.rls_system() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$ SELECT coalesce(current_setting('app.rls_system', true) = 'true', false) $$;

ALTER TABLE platform.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.contract_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.contract_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.ingestion_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.outbox_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON platform.devices
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.contracts
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.contract_versions
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.contract_mappings
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.ingestion_records
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.idempotency_records
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());
CREATE POLICY tenant_isolation ON platform.outbox_events
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());

-- platform.tenants is the tenant directory, read before a tenant is known, so it has no policy.
-- Grants are limited to the tables the application uses; flyway_schema_history stays unreadable.
DO $$
DECLARE
    app_role constant text := '${app_role}';
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA platform TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON
        platform.tenants, platform.devices, platform.contracts, platform.contract_versions,
        platform.contract_mappings, platform.ingestion_records, platform.idempotency_records,
        platform.outbox_events TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA platform TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION platform.current_tenant(), platform.rls_system() TO %I', app_role);

    -- Objects added by later migrations (run by this role) are usable by the application without a new grant.
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT USAGE, SELECT ON SEQUENCES TO %I', app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT EXECUTE ON FUNCTIONS TO %I', app_role);

    -- Registering a tenant creates its own schema at runtime.
    EXECUTE format('GRANT CREATE ON DATABASE %I TO %I', current_database(), app_role);
END
$$;
