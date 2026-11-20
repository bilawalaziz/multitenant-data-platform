package com.platform.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.ingestion.device.Device;
import com.platform.ingestion.device.DeviceRepository;
import com.platform.ingestion.device.DeviceStatus;
import com.platform.ingestion.observability.OutboxGaugeRefresher;
import com.platform.ingestion.persistence.TenantScopedDataSource;
import com.platform.ingestion.tenant.TenantScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tenant isolation enforced by PostgreSQL itself, independent of the tenant filters in the repositories. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RowLevelSecurityIT extends AbstractIntegrationTest {

    private static final String TENANT_A = "tenant-rls-a";
    private static final String TENANT_B = "tenant-rls-b";

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private OutboxGaugeRefresher gaugeRefresher;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID tenantA;
    private UUID tenantB;
    private UUID deviceA;
    private UUID deviceB;

    @BeforeAll
    void setUpTenants() throws Exception {
        registerTenant(TENANT_A);
        registerTenant(TENANT_B);
        tenantA = tenantId(TENANT_A);
        tenantB = tenantId(TENANT_B);
        deviceA = insertDevice(tenantA, "RLS-A");
        deviceB = insertDevice(tenantB, "RLS-B");
    }

    private UUID tenantId(String code) {
        return jdbc.queryForObject("SELECT id FROM platform.tenants WHERE tenant_code = ?", UUID.class, code);
    }

    /** Inserted as the owner so the fixture does not depend on the policies under test. */
    private UUID insertDevice(UUID tenantId, String deviceId) {
        jdbc.update(
                "INSERT INTO platform.devices (id, tenant_id, device_id, status, created_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', now()) ON CONFLICT DO NOTHING",
                UUID.randomUUID(),
                tenantId,
                deviceId);
        return jdbc.queryForObject(
                "SELECT id FROM platform.devices WHERE tenant_id = ? AND device_id = ?",
                UUID.class,
                tenantId,
                deviceId);
    }

    @Test
    void applicationRoleIsNeitherSuperuserNorOwnerNorBypassesPolicies() {
        Map<String, Object> role = appJdbc.queryForMap(
                "SELECT r.rolname, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user");
        Integer ownedTables = appJdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'platform' AND tableowner = current_user",
                Integer.class);
        String migratedBy = jdbc.queryForObject(
                "SELECT installed_by FROM platform.flyway_schema_history WHERE version = '3'", String.class);

        assertThat(role.get("rolname")).isEqualTo(APP_ROLE);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
        assertThat(ownedTables).isZero();
        assertThat(migratedBy).isEqualTo(POSTGRES.getUsername());
    }

    @Test
    void queryWithoutTenantFilterCannotSeeAnotherTenantsRow() {
        try (TenantScope scope = TenantScope.tenant(tenantA)) {
            assertThat(devices.findById(deviceB)).isEmpty();
            assertThat(devices.findById(deviceA)).isPresent();
            assertThat(devices.findAll())
                    .allSatisfy(d -> assertThat(d.getTenantId()).isEqualTo(tenantA));
        }
    }

    @Test
    void insertForAnotherTenantViolatesThePolicy() {
        try (TenantScope scope = TenantScope.tenant(tenantA)) {
            assertThatThrownBy(() -> devices.saveAndFlush(new Device(
                            UUID.randomUUID(), tenantB, "FOREIGN", null, DeviceStatus.ACTIVE, Instant.now())))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void updateAndDeleteDoNotReachAnotherTenantsRow() {
        try (TenantScope scope = TenantScope.tenant(tenantA)) {
            assertThat(appJdbc.update("UPDATE platform.devices SET description = 'x' WHERE id = ?", deviceB))
                    .isZero();
            assertThat(appJdbc.update("DELETE FROM platform.devices WHERE id = ?", deviceB))
                    .isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.devices WHERE id = ?", Integer.class, deviceB))
                .isOne();
    }

    @Test
    void noScopeReturnsNoTenantOwnedRows() {
        assertThat(count(appJdbc, "platform.devices")).isZero();
        assertThat(count(appJdbc, "platform.outbox_events")).isZero();
        assertThat(count(jdbc, "platform.devices")).isPositive();
        // the tenant directory stays readable before a tenant is known
        assertThat(count(appJdbc, "platform.tenants")).isPositive();
    }

    @Test
    void systemScopeSpansTenants() {
        try (TenantScope scope = TenantScope.system()) {
            assertThat(devices.findById(deviceA)).isPresent();
            assertThat(devices.findById(deviceB)).isPresent();
        }
    }

    @Test
    void transactionOpenedBeforeTheScopeKeepsTheEarlierSetting() {
        // The setting is applied when the connection is taken, so scopes must be opened before transactions.
        Integer seen = tx.execute(s -> {
            try (TenantScope scope = TenantScope.tenant(tenantA)) {
                return appJdbc.queryForObject(
                        "SELECT count(*) FROM platform.devices WHERE id = ?", Integer.class, deviceA);
            }
        });
        assertThat(seen).isZero();
    }

    @Test
    void reusedPooledConnectionNeverCarriesThePreviousScope() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(APP_ROLE);
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcTemplate single = new JdbcTemplate(new TenantScopedDataSource(pool));
            String backend = "SELECT pg_backend_pid()";
            String visible = "SELECT string_agg(device_id, ',' ORDER BY device_id) FROM platform.devices "
                    + "WHERE device_id IN ('RLS-A', 'RLS-B')";

            Integer pid;
            try (TenantScope scope = TenantScope.tenant(tenantA)) {
                pid = single.queryForObject(backend, Integer.class);
                assertThat(single.queryForObject(visible, String.class)).isEqualTo("RLS-A");
            }
            try (TenantScope scope = TenantScope.tenant(tenantB)) {
                assertThat(single.queryForObject(backend, Integer.class)).isEqualTo(pid);
                assertThat(single.queryForObject(visible, String.class)).isEqualTo("RLS-B");
            }
            // scope closed: the same physical connection must have been reset, not left on tenant B
            assertThat(single.queryForObject(backend, Integer.class)).isEqualTo(pid);
            assertThat(single.queryForObject(visible, String.class)).isNull();
            try (TenantScope outer = TenantScope.tenant(tenantA)) {
                try (TenantScope inner = TenantScope.system()) {
                    assertThat(single.queryForObject(visible, String.class)).isEqualTo("RLS-A,RLS-B");
                }
                assertThat(single.queryForObject(visible, String.class)).isEqualTo("RLS-A");
            }
        }
    }

    /** The refresher swallows errors, so a missing system scope would silently report zero to the backlog alert. */
    @Test
    void outboxGaugesCountPendingRowsOfEveryTenant() throws Exception {
        newOutboxRow(TENANT_A, "RLS-A", Instant.now());
        newOutboxRow(TENANT_B, "RLS-B", Instant.now());

        gaugeRefresher.refresh();

        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_events WHERE status = 'PENDING'", Integer.class);
        assertThat(pending).isGreaterThanOrEqualTo(2);
        assertThat(meterRegistry.get("outbox.pending.events").gauge().value()).isEqualTo(pending.doubleValue());
    }

    private static int count(JdbcTemplate template, String table) {
        Integer n = template.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
