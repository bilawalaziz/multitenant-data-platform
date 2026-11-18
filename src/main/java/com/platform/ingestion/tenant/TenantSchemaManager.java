package com.platform.ingestion.tenant;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates and accesses the per-tenant PostgreSQL schema. Identifiers cannot be bound as parameters,
 * so the schema name is derived only from a validated tenant code, re-validated here and quoted.
 */
@Component
public class TenantSchemaManager {

    public static final Pattern TENANT_CODE = Pattern.compile("^[a-z][a-z0-9-]{2,30}$");
    private static final Pattern SCHEMA_NAME = Pattern.compile("^ts_[a-z][a-z0-9_]{2,30}$");
    private static final String EXAMPLE_KEY = "data_retention_days";
    private static final String EXAMPLE_VALUE = "30";

    private final JdbcTemplate jdbc;

    public TenantSchemaManager(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String schemaNameFor(String tenantCode) {
        if (tenantCode == null || !TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("Invalid tenant code");
        }
        return requireValidSchemaName("ts_" + tenantCode.replace('-', '_'));
    }

    static String requireValidSchemaName(String schemaName) {
        if (schemaName == null || !SCHEMA_NAME.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Invalid tenant schema name");
        }
        return schemaName;
    }

    static String quoted(String schemaName) {
        return '"' + requireValidSchemaName(schemaName) + '"';
    }

    /** Idempotent. Runs inside the caller's transaction (PostgreSQL DDL is transactional). */
    public void createTenantSchema(String schemaName) {
        String schema = quoted(schemaName);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + schema + ".tenant_settings ("
                + "setting_key varchar(64) PRIMARY KEY, "
                + "setting_value varchar(500) NOT NULL, "
                + "updated_at timestamptz NOT NULL DEFAULT now())");
        jdbc.update(
                "INSERT INTO " + schema + ".tenant_settings (setting_key, setting_value) VALUES (?, ?) "
                        + "ON CONFLICT (setting_key) DO NOTHING",
                EXAMPLE_KEY,
                EXAMPLE_VALUE);
    }

    public boolean schemaExists(String schemaName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                requireValidSchemaName(schemaName));
        return count != null && count > 0;
    }

    public Map<String, String> readSettings(String schemaName) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT setting_key, setting_value FROM " + quoted(schemaName)
                        + ".tenant_settings ORDER BY setting_key",
                rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                });
        return result;
    }

    public void upsertSettings(String schemaName, Map<String, String> settings) {
        String schema = quoted(schemaName);
        try {
            for (Map.Entry<String, String> e : settings.entrySet()) {
                jdbc.update(
                        "INSERT INTO " + schema + ".tenant_settings (setting_key, setting_value, updated_at) "
                                + "VALUES (?, ?, now()) ON CONFLICT (setting_key) DO UPDATE "
                                + "SET setting_value = EXCLUDED.setting_value, updated_at = now()",
                        e.getKey(),
                        e.getValue());
            }
        } catch (DataAccessException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Tenant settings could not be stored");
        }
    }
}
