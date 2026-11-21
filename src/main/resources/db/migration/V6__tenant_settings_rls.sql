-- Tenant settings move from one table per tenant schema (created at runtime by the application role) into a single
-- platform table under row-level security, like every other tenant-owned table. With no runtime DDL left, the
-- application role no longer needs CREATE on the database.

CREATE TABLE platform.tenant_settings (
    tenant_id      uuid         NOT NULL REFERENCES platform.tenants (id) ON DELETE CASCADE,
    setting_key    varchar(64)  NOT NULL,
    setting_value  varchar(500) NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, setting_key)
);

ALTER TABLE platform.tenant_settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON platform.tenant_settings
    USING (tenant_id = platform.current_tenant() OR platform.rls_system())
    WITH CHECK (tenant_id = platform.current_tenant() OR platform.rls_system());

-- The default privileges from V3 already give the application role DML on this table.

-- Copy what the per-tenant tables hold. Those schemas and tables were created by the application role, so they are
-- owned by it: a superuser migration role reads them directly, but a non-superuser owner has no privileges on them.
-- In that case the copy stops with the statements to run as the application role instead of dropping settings.
-- Existence and privileges are looked up in the catalogs by oid: resolving the table by name would itself fail with
-- "permission denied for schema" before the message below could explain what to grant.
DO $$
DECLARE
    t record;
    src text;
    schema_oid oid;
    table_oid oid;
BEGIN
    FOR t IN SELECT id, schema_name FROM platform.tenants ORDER BY tenant_code LOOP
        src := format('%I.tenant_settings', t.schema_name);
        SELECT n.oid, c.oid INTO schema_oid, table_oid
        FROM pg_namespace n
        JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = 'tenant_settings' AND c.relkind = 'r'
        WHERE n.nspname = t.schema_name;
        IF table_oid IS NULL THEN
            CONTINUE;
        END IF;
        IF NOT has_schema_privilege(schema_oid, 'USAGE') OR NOT has_table_privilege(table_oid, 'SELECT') THEN
            RAISE EXCEPTION 'Cannot read % as %. Connected as the application role, run: GRANT USAGE ON SCHEMA % TO %; GRANT SELECT ON % TO %; then migrate again.',
                src, current_user, quote_ident(t.schema_name), quote_ident(current_user), src, quote_ident(current_user);
        END IF;
        EXECUTE format(
            'INSERT INTO platform.tenant_settings (tenant_id, setting_key, setting_value, updated_at) '
            'SELECT %L, setting_key, setting_value, updated_at FROM %s ON CONFLICT DO NOTHING',
            t.id, src);
    END LOOP;
END
$$;

-- The ts_* schemas are left in place so nothing is destroyed here. After checking that platform.tenant_settings holds
-- every setting, an operator can drop them: DROP SCHEMA ts_<code> CASCADE.

-- REVOKE only removes what this role granted and otherwise just warns, so the result is checked.
DO $$
BEGIN
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM %I', current_database(), '${app_role}');
    IF has_database_privilege('${app_role}', current_database(), 'CREATE') THEN
        RAISE EXCEPTION 'Role % still has CREATE on database %, granted by another role. Revoke it as that role or as the database owner, then migrate again.',
            '${app_role}', current_database();
    END IF;
END
$$;
