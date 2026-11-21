package com.platform.ingestion.tenant;

import com.platform.ingestion.common.ApiException;
import com.platform.ingestion.common.ErrorCode;
import com.platform.ingestion.common.FieldError;
import com.platform.ingestion.messaging.TopicProvisioner;
import com.platform.ingestion.tenant.TenantDtos.SettingsResponse;
import com.platform.ingestion.tenant.TenantDtos.TenantResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);
    private static final Pattern TENANT_CODE = Pattern.compile("^[a-z][a-z0-9-]{2,30}$");
    private static final String DEFAULT_RETENTION_KEY = "data_retention_days";
    private static final String DEFAULT_RETENTION_DAYS = "30";
    private static final Pattern SETTING_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final TenantRepository tenants;
    private final TenantSettingsRepository settings;
    private final TopicProvisioner topicProvisioner;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TenantService(
            TenantRepository tenants,
            TenantSettingsRepository settings,
            TopicProvisioner topicProvisioner,
            TransactionTemplate tx,
            Clock clock) {
        this.tenants = tenants;
        this.settings = settings;
        this.topicProvisioner = topicProvisioner;
        this.tx = tx;
        this.clock = clock;
    }

    /** Record + default settings are created atomically; the Kafka topic is best effort (publisher ensures it later). */
    public TenantResponse register(String tenantCode, String displayName) {
        // The tenant does not exist yet, so there is no tenant to scope to; registration runs as the platform.
        Tenant created;
        try (TenantScope ignored = TenantScope.system()) {
            created = registerInScope(tenantCode, displayName);
        }
        if (!topicProvisioner.tryEnsureTopic(created.getKafkaTopic())) {
            log.warn(
                    "Kafka topic {} could not be created at registration; the outbox publisher will ensure it later",
                    created.getKafkaTopic());
        }
        return TenantResponse.from(created);
    }

    private Tenant registerInScope(String tenantCode, String displayName) {
        return tx.execute(status -> {
            if (tenants.existsByTenantCode(tenantCode)) {
                throw new ApiException(ErrorCode.CONFLICT, "Tenant '" + tenantCode + "' already exists");
            }
            String schemaName = schemaNameFor(tenantCode);
            Tenant tenant = tenants.saveAndFlush(new Tenant(
                    UUID.randomUUID(),
                    tenantCode,
                    displayName,
                    TenantStatus.ACTIVE,
                    schemaName,
                    tenantCode + ".readings",
                    clock.instant()));
            settings.insertIfAbsent(tenant.getId(), DEFAULT_RETENTION_KEY, DEFAULT_RETENTION_DAYS);
            return tenant;
        });
    }

    /** Kept for the API response; settings are no longer stored in a per-tenant schema. */
    private static String schemaNameFor(String tenantCode) {
        if (tenantCode == null || !TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("Invalid tenant code");
        }
        return "ts_" + tenantCode.replace('-', '_');
    }

    public List<TenantResponse> list() {
        return tenants.findAllByOrderByTenantCodeAsc().stream()
                .map(TenantResponse::from)
                .toList();
    }

    public TenantResponse get(String tenantCode) {
        return TenantResponse.from(requireByCode(tenantCode));
    }

    public Tenant requireByCode(String tenantCode) {
        return tenants.findByTenantCode(tenantCode).orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    public TenantContext requireContext(String tenantCode) {
        return requireByCode(tenantCode).toContext();
    }

    public SettingsResponse getSettings(String tenantCode) {
        Tenant tenant = requireByCode(tenantCode);
        return new SettingsResponse(tenantCode, tenant.getSchemaName(), settings.findAll(tenant.getId()));
    }

    public SettingsResponse updateSettings(String tenantCode, Map<String, String> newSettings) {
        List<FieldError> errors = newSettings.keySet().stream()
                .filter(k -> !SETTING_KEY.matcher(k).matches())
                .map(k -> new FieldError("settings." + k, "Setting key must match ^[a-z][a-z0-9_]{0,63}$"))
                .toList();
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Invalid setting key", errors);
        }
        Tenant tenant = requireByCode(tenantCode);
        try {
            tx.executeWithoutResult(s -> newSettings.forEach((k, v) -> settings.upsert(tenant.getId(), k, v)));
        } catch (DataAccessException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "Tenant settings could not be stored");
        }
        return getSettings(tenantCode);
    }
}
