package com.platform.ingestion.tenant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Settings live in one table under row-level security; the tenant id is also passed explicitly so the queries stay
 * correct and readable even where the scope is the platform's.
 */
@Repository
public class TenantSettingsRepository {

    private final JdbcTemplate jdbc;

    public TenantSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> findAll(UUID tenantId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT setting_key, setting_value FROM platform.tenant_settings "
                        + "WHERE tenant_id = ? ORDER BY setting_key",
                rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                },
                tenantId);
        return result;
    }

    public void upsert(UUID tenantId, String key, String value) {
        jdbc.update(
                "INSERT INTO platform.tenant_settings (tenant_id, setting_key, setting_value, updated_at) "
                        + "VALUES (?, ?, ?, now()) ON CONFLICT (tenant_id, setting_key) DO UPDATE "
                        + "SET setting_value = EXCLUDED.setting_value, updated_at = now()",
                tenantId,
                key,
                value);
    }

    /** Keeps an existing value, so it is safe to repeat. */
    public void insertIfAbsent(UUID tenantId, String key, String value) {
        jdbc.update(
                "INSERT INTO platform.tenant_settings (tenant_id, setting_key, setting_value) VALUES (?, ?, ?) "
                        + "ON CONFLICT (tenant_id, setting_key) DO NOTHING",
                tenantId,
                key,
                value);
    }
}
