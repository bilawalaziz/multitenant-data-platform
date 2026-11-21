package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V6 on a database that already holds per-tenant settings tables, created and owned by the application role as the
 * earlier runtime code did. Uses its own container so the shared Spring context and database stay untouched.
 */
class FlywayTenantSettingsMigrationIT {

    private static final String INSERT_TENANT =
            "INSERT INTO platform.tenants (id, tenant_code, display_name, status, schema_name, kafka_topic, created_at) "
                    + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, now())";

    @Test
    void migrationKeepsEveryExistingSettingAndRevokesSchemaCreation() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                .withInitScript("db/create-app-role.sql")) {
            pg.start();
            JdbcTemplate owner =
                    new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            String canCreate = "SELECT has_database_privilege('sensor_app', current_database(), 'CREATE')";

            flyway(pg).target("5").load().migrate();
            assertThat(owner.queryForObject(canCreate, Boolean.class)).isTrue();

            UUID tenantId = UUID.randomUUID();
            owner.update(INSERT_TENANT, tenantId, "legacy-co", "Legacy", "ts_legacy_co", "legacy-co.readings");
            // A tenant whose schema was never created must not break the copy.
            owner.update(INSERT_TENANT, UUID.randomUUID(), "no-schema", "None", "ts_no_schema", "no-schema.readings");
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "sensor_app", "sensor_app_test_pw");
                    Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA \"ts_legacy_co\"");
                st.execute("CREATE TABLE \"ts_legacy_co\".tenant_settings (setting_key varchar(64) PRIMARY KEY, "
                        + "setting_value varchar(500) NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
                st.execute("INSERT INTO \"ts_legacy_co\".tenant_settings (setting_key, setting_value) VALUES "
                        + "('data_retention_days', '45'), ('alert_email', 'ops@legacy.example')");
            }

            flyway(pg).load().migrate();

            List<Map<String, Object>> rows = owner.queryForList(
                    "SELECT setting_key, setting_value FROM platform.tenant_settings "
                            + "WHERE tenant_id = ? ORDER BY setting_key",
                    tenantId);
            assertThat(rows)
                    .extracting(r -> r.get("setting_key") + "=" + r.get("setting_value"))
                    .containsExactly("alert_email=ops@legacy.example", "data_retention_days=45");
            assertThat(owner.queryForObject("SELECT count(*) FROM platform.tenant_settings", Integer.class))
                    .isEqualTo(2);
            assertThat(owner.queryForObject(canCreate, Boolean.class)).isFalse();
            // old schemas stay for an operator to drop
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'ts_legacy_co'",
                            Integer.class))
                    .isOne();
        }
    }

    private static FluentConfiguration flyway(PostgreSQLContainer<?> pg) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .schemas("platform")
                .defaultSchema("platform")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", "sensor_app"));
    }
}
